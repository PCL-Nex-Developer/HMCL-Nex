/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.plugin.store;

import com.google.gson.JsonParseException;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.plugin.PluginContainer;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginVersion;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.HttpRequest;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Resolves validated remote registries, checks compatibility, and downloads verified plugin packages atomically.
@NotNullByDefault
public final class PluginStoreManager {
    /// Official HMCL Nex plugin registry.
    public static final String DEFAULT_REGISTRY_URL =
            "https://raw.githubusercontent.com/PCL-Nex-Developer/HMCL-Nex-Plugin-Store/main/plugins.json";

    /// Hard upper bound for any downloaded plugin package.
    private static final long MAX_PACKAGE_BYTES = 512L * 1024L * 1024L;

    /// Maximum plugin manifest bytes inspected before an atomic package replacement.
    private static final int MAX_PLUGIN_MANIFEST_BYTES = 1024 * 1024;

    /// Extracts the first Java feature number from registry text.
    private static final Pattern JAVA_VERSION_PATTERN = Pattern.compile("(\\d+)");

    /// Currently loaded validated registry.
    private @Nullable PluginStoreRegistry registry;

    /// URL of the active registry.
    private String registryUrl = DEFAULT_REGISTRY_URL;

    /// Known registry URLs displayed by the source selector.
    private final List<String> registryUrls = new ArrayList<>();

    /// Validated repository manifests cached by URL.
    private final Map<String, PluginStoreManifest> manifestCache = new HashMap<>();

    /// Creates a store manager configured with the official registry.
    public PluginStoreManager() {
        registryUrls.add(DEFAULT_REGISTRY_URL);
    }

    /// Loads and validates a plugin registry from a secure remote URL.
    ///
    /// @param registryUrl registry URL
    /// @throws IOException if transport, parsing, URL policy, or validation fails
    public void loadRegistry(String registryUrl) throws IOException {
        validateRemoteUrl(registryUrl, "plugin registry");
        LOG.info("Loading plugin registry from: " + registryUrl);
        try {
            String content = HttpRequest.GET(registryUrl).getString();
            @Nullable PluginStoreRegistry loadedRegistry = JsonUtils.GSON.fromJson(
                    content,
                    PluginStoreRegistry.class
            );
            if (loadedRegistry == null) {
                throw new IOException("Empty plugin registry: " + registryUrl);
            }
            loadedRegistry.validate();
            for (PluginStoreRegistry.PluginStoreEntry entry : loadedRegistry.getPlugins()) {
                validateRemoteUrl(entry.getManifestUrl(), "plugin manifest");
            }

            registry = loadedRegistry;
            this.registryUrl = registryUrl;
            manifestCache.clear();
            LOG.info("Loaded " + loadedRegistry.getPlugins().size() + " plugins from registry");
        } catch (JsonParseException exception) {
            throw new IOException("Failed to parse plugin registry", exception);
        }
    }

    /// Loads the official plugin registry.
    ///
    /// @throws IOException if loading fails
    public void loadDefaultRegistry() throws IOException {
        loadRegistry(registryUrls.get(0));
    }

    /// Resolves and validates one plugin repository manifest.
    ///
    /// @param pluginId expected plugin ID
    /// @param manifestUrl repository manifest URL
    /// @return validated repository manifest
    /// @throws IOException if transport, parsing, identity, or schema validation fails
    public PluginStoreManifest getPluginManifest(String pluginId, String manifestUrl) throws IOException {
        @Nullable PluginStoreManifest cached = manifestCache.get(manifestUrl);
        if (cached != null) {
            if (!pluginId.equals(cached.getId())) {
                throw new IOException("Cached plugin manifest ID mismatch for " + pluginId);
            }
            return cached;
        }

        validateRemoteUrl(manifestUrl, "plugin manifest");
        LOG.info("Fetching plugin manifest from: " + manifestUrl);
        try {
            String content = HttpRequest.GET(manifestUrl).getString();
            @Nullable PluginStoreManifest manifest = JsonUtils.GSON.fromJson(content, PluginStoreManifest.class);
            if (manifest == null) {
                throw new IOException("Empty plugin manifest: " + manifestUrl);
            }
            manifest.validate(pluginId);
            for (PluginStoreManifest.PluginVersionEntry version : manifest.getVersions()) {
                validateRemoteUrl(version.getPackageUrl(), "plugin package");
            }
            manifestCache.put(manifestUrl, manifest);
            return manifest;
        } catch (JsonParseException exception) {
            throw new IOException("Failed to parse plugin manifest", exception);
        }
    }

    /// Resolves all registry entries, retaining unavailable repositories as partial items.
    ///
    /// @return resolved store items
    public @Unmodifiable List<PluginStoreItem> getStoreItems() {
        @Nullable PluginStoreRegistry currentRegistry = registry;
        if (currentRegistry == null) {
            return List.of();
        }

        List<PluginStoreItem> items = new ArrayList<>();
        for (PluginStoreRegistry.PluginStoreEntry entry : currentRegistry.getPlugins()) {
            try {
                items.add(new PluginStoreItem(
                        entry,
                        getPluginManifest(entry.getId(), entry.getManifestUrl())
                ));
            } catch (IOException exception) {
                LOG.warning("Failed to load plugin manifest: " + entry.getId(), exception);
                items.add(new PluginStoreItem(entry, null));
            }
        }
        return List.copyOf(items);
    }

    /// Downloads a package to a temporary file, validates size and SHA-256, then atomically replaces `pluginId.npl`.
    ///
    /// @param pluginId validated plugin ID
    /// @param version remote version metadata
    /// @param targetDirectory installed plugin directory
    /// @return verified installed package path
    /// @throws IOException if compatibility, transport, size, checksum, or replacement fails
    public Path downloadPlugin(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry version,
            Path targetDirectory
    ) throws IOException {
        validateCompatibility(version);
        validateRemoteUrl(version.getPackageUrl(), "plugin package");

        Files.createDirectories(targetDirectory);
        Path targetFile = targetDirectory.resolve(pluginId + ".npl");
        Path temporaryFile = targetDirectory.resolve(
                "." + pluginId + "-" + UUID.randomUUID() + ".download"
        );
        @Nullable Long declaredSize = version.getSize();
        if (declaredSize != null && declaredSize > MAX_PACKAGE_BYTES) {
            throw new IOException("Plugin package exceeds the maximum allowed size");
        }

        MessageDigest digest = createSha256();
        long totalBytes = 0;
        byte[] buffer = new byte[8192];
        LOG.info("Downloading plugin " + pluginId + " v" + version.getVersion()
                + " from " + version.getPackageUrl());

        @Nullable HttpURLConnection connection = null;
        try {
            connection = NetworkUtils.resolveConnection(
                    HttpRequest.GET(version.getPackageUrl()).createConnection()
            );
            validateRemoteUrl(connection.getURL().toString(), "plugin package redirect");
            int responseCode = connection.getResponseCode();
            if (responseCode / 100 != 2) {
                throw new IOException("Plugin package request failed with HTTP " + responseCode);
            }

            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(temporaryFile))) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    totalBytes = Math.addExact(totalBytes, read);
                    if (totalBytes > MAX_PACKAGE_BYTES || declaredSize != null && totalBytes > declaredSize) {
                        throw new IOException("Plugin package exceeds its declared or maximum size");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
        } catch (ArithmeticException exception) {
            throw new IOException("Plugin package size overflow", exception);
        } catch (IOException exception) {
            Files.deleteIfExists(temporaryFile);
            throw exception;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        try {
            if (declaredSize != null && totalBytes != declaredSize) {
                throw new IOException("Plugin package size mismatch. Expected " + declaredSize + ", got " + totalBytes);
            }
            String actualHash = toHex(digest.digest());
            if (!actualHash.equalsIgnoreCase(version.getSha256())) {
                throw new IOException("Plugin checksum mismatch. Expected " + version.getSha256()
                        + ", got " + actualHash);
            }
            validateDownloadedPackage(temporaryFile, pluginId, version.getPluginApiVersion());
            try {
                Files.move(
                        temporaryFile,
                        targetFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.info("Downloaded and verified plugin package: " + targetFile);
            return targetFile;
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    /// Validates launcher, Java, and plugin API requirements before downloading a package.
    ///
    /// @param version remote version metadata
    /// @throws IOException if the current runtime is incompatible
    public void validateCompatibility(PluginStoreManifest.PluginVersionEntry version) throws IOException {
        String minimumLauncher = version.getMinLauncherVersion();
        String currentLauncher = Metadata.VERSION.toLowerCase(Locale.ROOT);
        if (!minimumLauncher.isBlank()
                && !currentLauncher.contains("snapshot")
                && !currentLauncher.contains("develop")
                && PluginVersion.compare(Metadata.VERSION, minimumLauncher) < 0) {
            throw new IOException("This plugin requires HMCL " + minimumLauncher + " or newer");
        }

        String requiredJava = version.getRequiredJavaVersion();
        if (!requiredJava.isBlank()) {
            Matcher matcher = JAVA_VERSION_PATTERN.matcher(requiredJava);
            if (!matcher.find()) {
                throw new IOException("Invalid requiredJavaVersion: " + requiredJava);
            }
            int requiredFeature;
            try {
                requiredFeature = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException exception) {
                throw new IOException("Invalid requiredJavaVersion: " + requiredJava, exception);
            }
            if (Runtime.version().feature() < requiredFeature) {
                throw new IOException("This plugin requires Java " + requiredFeature + " or newer");
            }
        }
    }

    /// Returns whether a remote version is newer than the installed plugin manifest.
    ///
    /// @param installed installed plugin container or `null`
    /// @param remoteVersion remote version or `null`
    /// @return whether an update is available
    public boolean hasUpdate(
            @Nullable PluginContainer installed,
            @Nullable PluginStoreManifest.PluginVersionEntry remoteVersion
    ) {
        return installed != null
                && remoteVersion != null
                && PluginVersion.compare(
                remoteVersion.getVersion(),
                installed.getManifest().getVersion()
        ) > 0;
    }

    /// Compares two plugin versions using the shared semantic-version-compatible comparator.
    ///
    /// @param left first version
    /// @param right second version
    /// @return version ordering
    public static int compareVersion(String left, String right) {
        return PluginVersion.compare(left, right);
    }

    /// Adds a custom secure registry URL to the source selector.
    ///
    /// @param url registry URL
    /// @throws IllegalArgumentException if the URL violates remote transport policy
    public void addCustomRegistry(String url) {
        try {
            validateRemoteUrl(url, "plugin registry");
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
        if (!registryUrls.contains(url)) {
            registryUrls.add(url);
        }
    }

    /// Adds and loads a registry URL.
    ///
    /// @param url registry URL
    /// @throws IOException if validation or loading fails
    public void setActiveRegistryUrl(String url) throws IOException {
        validateRemoteUrl(url, "plugin registry");
        if (!registryUrls.contains(url)) {
            registryUrls.add(url);
        }
        loadRegistry(url);
    }

    /// Returns the active registry URL.
    ///
    /// @return registry URL
    public String getRegistryUrl() {
        return registryUrl;
    }

    /// Returns the currently loaded registry.
    ///
    /// @return registry or `null`
    public @Nullable PluginStoreRegistry getRegistry() {
        return registry;
    }

    /// Returns an immutable snapshot of known registry URLs.
    ///
    /// @return registry URLs
    public @Unmodifiable List<String> getRegistryUrls() {
        return List.copyOf(registryUrls);
    }

    /// Clears resolved repository manifests.
    public void clearCache() {
        manifestCache.clear();
    }

    /// Enforces HTTPS for remote hosts while allowing loopback HTTP registries used for local development.
    ///
    /// @param url URL to validate
    /// @param purpose value used in diagnostics
    /// @throws IOException if the URL is malformed or insecure
    private static void validateRemoteUrl(String url, String purpose) throws IOException {
        final URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException exception) {
            throw new IOException("Invalid " + purpose + " URL: " + url, exception);
        }
        @Nullable String scheme = uri.getScheme();
        @Nullable String host = uri.getHost();
        if (scheme == null || host == null) {
            throw new IOException("Invalid " + purpose + " URL: " + url);
        }
        boolean loopback = host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("[::1]");
        if (!scheme.equalsIgnoreCase("https") && !(scheme.equalsIgnoreCase("http") && loopback)) {
            throw new IOException("Insecure " + purpose + " URL is not allowed: " + url);
        }
    }

    /// Creates a SHA-256 message digest.
    ///
    /// @return digest instance
    /// @throws IOException if SHA-256 is unavailable
    private static MessageDigest createSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    /// Validates the internal package manifest before replacing a working installed package.
    ///
    /// @param packageFile verified temporary `.npl` file
    /// @param expectedPluginId plugin ID from the registry
    /// @param expectedApiVersion plugin manifest schema declared by the remote version entry
    /// @throws IOException if the package identity or schema does not match remote metadata
    private static void validateDownloadedPackage(
            Path packageFile,
            String expectedPluginId,
            int expectedApiVersion
    ) throws IOException {
        try (ZipFile zipFile = new ZipFile(packageFile.toFile())) {
            @Nullable ZipEntry manifestEntry = zipFile.getEntry("plugin.json");
            if (manifestEntry == null || manifestEntry.isDirectory()) {
                throw new IOException("Downloaded package has no plugin.json");
            }
            if (manifestEntry.getSize() > MAX_PLUGIN_MANIFEST_BYTES) {
                throw new IOException("Downloaded package manifest is too large");
            }

            byte[] manifestBytes;
            try (InputStream input = zipFile.getInputStream(manifestEntry)) {
                manifestBytes = input.readNBytes(MAX_PLUGIN_MANIFEST_BYTES + 1);
            }
            if (manifestBytes.length > MAX_PLUGIN_MANIFEST_BYTES) {
                throw new IOException("Downloaded package manifest is too large");
            }

            PluginManifest packageManifest = PluginManifest.fromJson(new java.io.StringReader(
                    new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8)
            ));
            if (!expectedPluginId.equals(packageManifest.getId())) {
                throw new IOException("Downloaded package ID " + packageManifest.getId()
                        + " does not match registry entry " + expectedPluginId);
            }
            if (packageManifest.getSchemaVersion() != expectedApiVersion) {
                throw new IOException("Downloaded package schemaVersion " + packageManifest.getSchemaVersion()
                        + " does not match pluginApiVersion " + expectedApiVersion);
            }
        }
    }

    /// Converts digest bytes to lower-case hexadecimal text.
    ///
    /// @param bytes digest bytes
    /// @return hexadecimal digest
    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}

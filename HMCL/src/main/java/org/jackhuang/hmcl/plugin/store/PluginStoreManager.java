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
import org.jackhuang.hmcl.plugin.PluginContainer;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.HttpRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Plugin store manager for downloading plugins from remote repositories.
 */
public class PluginStoreManager {
    public static final String DEFAULT_REGISTRY_URL = "https://raw.githubusercontent.com/PCL-Nex-Developer/HMCL-Nex-Plugin-Store/main/plugins.json";

    private PluginStoreRegistry registry;
    private String registryUrl = DEFAULT_REGISTRY_URL;
    private final List<String> registryUrls = new ArrayList<>();
    private final Map<String, PluginStoreManifest> manifestCache = new HashMap<>();

    public PluginStoreManager() {
        registryUrls.add(DEFAULT_REGISTRY_URL);
    }

    /**
     * Load plugin registry from remote URL.
     */
    public void loadRegistry(String registryUrl) throws IOException {
        LOG.info("Loading plugin registry from: " + registryUrl);

        try {
            String content = HttpRequest.GET(registryUrl).getString();
            PluginStoreRegistry loadedRegistry = JsonUtils.GSON.fromJson(content, PluginStoreRegistry.class);
            if (loadedRegistry == null || loadedRegistry.getPlugins() == null) {
                throw new IOException("Invalid plugin registry: " + registryUrl);
            }
            registry = loadedRegistry;
            this.registryUrl = registryUrl;
            LOG.info("Loaded " + registry.getPlugins().size() + " plugins from registry");
        } catch (JsonParseException e) {
            throw new IOException("Failed to parse plugin registry", e);
        }
    }

    /**
     * Load the default plugin registry.
     */
    public void loadDefaultRegistry() throws IOException {
        loadRegistry(registryUrls.get(0));
    }

    /**
     * Get plugin manifest from remote URL.
     */
    public PluginStoreManifest getPluginManifest(String manifestUrl) throws IOException {
        PluginStoreManifest cached = manifestCache.get(manifestUrl);
        if (cached != null) {
            return cached;
        }

        LOG.info("Fetching plugin manifest from: " + manifestUrl);

        try {
            String content = HttpRequest.GET(manifestUrl).getString();
            PluginStoreManifest manifest = JsonUtils.GSON.fromJson(content, PluginStoreManifest.class);
            if (manifest == null || manifest.getVersions() == null || manifest.getVersions().isEmpty()) {
                throw new IOException("Invalid plugin manifest: " + manifestUrl);
            }
            manifestCache.put(manifestUrl, manifest);
            return manifest;
        } catch (JsonParseException e) {
            throw new IOException("Failed to parse plugin manifest", e);
        }
    }

    /**
     * Resolve all store entries with their manifests. Broken entries are skipped.
     */
    public List<PluginStoreItem> getStoreItems() {
        if (registry == null || registry.getPlugins() == null) {
            return Collections.emptyList();
        }

        List<PluginStoreItem> items = new ArrayList<>();
        for (PluginStoreRegistry.PluginStoreEntry entry : registry.getPlugins()) {
            try {
                items.add(new PluginStoreItem(entry, getPluginManifest(entry.getManifestUrl())));
            } catch (IOException e) {
                LOG.warning("Failed to load plugin manifest: " + entry.getId(), e);
                items.add(new PluginStoreItem(entry, null));
            }
        }
        return items;
    }

    /**
     * Download plugin package to specified path.
     */
    public Path downloadPlugin(String pluginId, PluginStoreManifest.PluginVersion version, Path targetDir) throws IOException {
        String packageUrl = version.getPackageUrl();
        String fileName = pluginId + "-" + version.getVersion() + ".npl";
        Path targetFile = targetDir.resolve(fileName);

        LOG.info("Downloading plugin: " + pluginId + " v" + version.getVersion());
        LOG.info("URL: " + packageUrl);

        Files.createDirectories(targetDir);
        Files.deleteIfExists(targetFile);

        try (InputStream in = HttpRequest.GET(packageUrl).createConnection().getInputStream()) {
            Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }

        if (version.getSha256() != null && !version.getSha256().isEmpty()) {
            String actualHash = calculateSha256(targetFile);
            if (!actualHash.equalsIgnoreCase(version.getSha256())) {
                Files.deleteIfExists(targetFile);
                throw new IOException("Checksum verification failed. Expected: " + version.getSha256() + ", Got: " + actualHash);
            }
            LOG.info("Checksum verified successfully");
        }

        LOG.info("Plugin downloaded successfully: " + targetFile);
        return targetFile;
    }

    public boolean hasUpdate(PluginContainer installed, PluginStoreManifest.PluginVersion remoteVersion) {
        if (installed == null || remoteVersion == null || remoteVersion.getVersion() == null) {
            return false;
        }
        return compareVersion(remoteVersion.getVersion(), installed.getManifest().getVersion()) > 0;
    }

    public static int compareVersion(String a, String b) {
        String[] left = normalizeVersion(a).split("\\.");
        String[] right = normalizeVersion(b).split("\\.");
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int l = i < left.length ? parseVersionPart(left[i]) : 0;
            int r = i < right.length ? parseVersionPart(right[i]) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private static String normalizeVersion(String version) {
        return version == null ? "0" : version.replaceFirst("^[vV]", "").replaceAll("[^0-9.]", ".");
    }

    private static int parseVersionPart(String part) {
        try {
            return part == null || part.isEmpty() ? 0 : Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Calculate SHA-256 hash of a file.
     */
    private String calculateSha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] fileBytes = Files.readAllBytes(file);
            byte[] hashBytes = digest.digest(fileBytes);

            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new IOException("Failed to calculate SHA-256", e);
        }
    }

    /**
     * Add a custom registry URL.
     */
    public void addCustomRegistry(String url) {
        if (!registryUrls.contains(url)) {
            registryUrls.add(url);
        }
    }

    public void setActiveRegistryUrl(String url) throws IOException {
        if (!registryUrls.contains(url)) {
            registryUrls.add(url);
        }
        loadRegistry(url);
    }

    public String getRegistryUrl() {
        return registryUrl;
    }

    /**
     * Get the loaded registry.
     */
    public PluginStoreRegistry getRegistry() {
        return registry;
    }

    /**
     * Get all registry URLs.
     */
    public List<String> getRegistryUrls() {
        return new ArrayList<>(registryUrls);
    }

    public void clearCache() {
        manifestCache.clear();
    }
}

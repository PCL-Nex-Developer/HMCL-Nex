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
package org.jackhuang.hmcl.plugin.mixin.bootstrap;

import com.google.gson.Gson;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/// Discovers enabled JVM plugins and relaunches HMCL with its premain Mixin instrumentation agent.
@NotNullByDefault
public final class HmclMixinBootstrap {
    /// System property that disables plugin Mixin discovery for recovery and diagnostics.
    public static final String DISABLE_PROPERTY = "hmcl.plugin.mixins.disabled";

    /// System property listing plugin IDs whose Mixin configurations are active in this process.
    public static final String ACTIVE_PROPERTY = "hmcl.plugin.mixins.active";

    /// System property set in the relaunched JVM whose premain agent owns Mixin transformation.
    public static final String AGENT_ACTIVE_PROPERTY = "hmcl.plugin.mixins.agent.active";

    /// Plugin manifest entry stored at the root of every `.npl` archive.
    private static final String PLUGIN_MANIFEST = "plugin.json";

    /// Persistent file containing enabled and pending-uninstall plugin IDs.
    private static final String PLUGIN_STATE_FILE = "plugin-states.json";

    /// Maximum accepted uncompressed size of one plugin manifest.
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;

    /// Maximum number of files accepted in an extracted plugin package.
    private static final int MAX_ARCHIVE_ENTRIES = 10_000;

    /// Maximum aggregate uncompressed bytes accepted from one plugin package.
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L;

    /// Plugin IDs accepted for cache directory names and dependency references.
    private static final Pattern PLUGIN_ID_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{1,127}");

    /// JSON codec used before the full launcher logging and settings systems are available.
    private static final Gson GSON = new Gson();

    /// Prevents construction of the startup utility.
    private HmclMixinBootstrap() {
    }

    /// Relaunches HMCL when at least one enabled JVM plugin declares Mixin configurations.
    ///
    /// The relaunched JVM calls this method again after premain sets [AGENT_ACTIVE_PROPERTY], preventing recursion.
    ///
    /// @param args launcher arguments
    /// @return whether control was transferred to an agent-enabled JVM
    public static boolean relaunchIfNeeded(String[] args) {
        if (Boolean.getBoolean(DISABLE_PROPERTY)) {
            return false;
        }

        if (Boolean.getBoolean(AGENT_ACTIVE_PROPERTY)) {
            @Nullable String activePlugins = System.getProperty(ACTIVE_PROPERTY);
            if (activePlugins != null && !activePlugins.isBlank()) {
                return false;
            }

            // Older restarts could inherit the command-line marker while dropping
            // the corresponding -javaagent argument. Treat that state as stale so
            // enabled Mixin plugins get a fresh agent-enabled relaunch.
            System.clearProperty(AGENT_ACTIVE_PROPERTY);
            report("Ignoring stale plugin Mixin agent marker without active configurations");
        }

        try {
            AgentConfiguration configuration = prepareAgentConfiguration();
            if (configuration.mixinConfigs.isEmpty()) {
                return false;
            }
            startAgentProcess(args);
            report("Relaunched HMCL with the plugin Mixin agent for "
                    + configuration.mixinConfigs.size() + " configuration(s)");
            return true;
        } catch (IOException exception) {
            report("Unable to relaunch HMCL with plugin Mixins; continuing without them", exception);
            System.setProperty(DISABLE_PROPERTY, "true");
            return false;
        }
    }

    /// Prepares immutable plugin paths, unique configurations, and active plugin IDs for premain.
    ///
    /// @return agent configuration, possibly with no Mixin configurations
    /// @throws IOException if package discovery or resource validation fails
    static AgentConfiguration prepareAgentConfiguration() throws IOException {
        @Unmodifiable List<PluginLaunchDescriptor> descriptors = discoverEnabledJvmPlugins(resolveLocalHome());
        @Unmodifiable List<String> configs = collectMixinConfigs(descriptors);
        for (String config : configs) {
            if (!validateMixinResource(descriptors, config)) {
                throw new IOException("Missing Mixin configuration resource: " + config);
            }
        }

        @Unmodifiable List<Path> classPathEntries = descriptors.stream()
                .flatMap(descriptor -> descriptor.classPath.stream())
                .distinct()
                .toList();
        @Unmodifiable List<String> activePluginIds = descriptors.stream()
                .filter(descriptor -> !descriptor.mixinConfigs.isEmpty())
                .map(descriptor -> descriptor.id)
                .distinct()
                .sorted()
                .toList();
        return new AgentConfiguration(classPathEntries, configs, activePluginIds);
    }

    /// Starts a new JVM with the current HMCL artifact installed as a premain Java agent.
    ///
    /// @param args launcher arguments
    /// @throws IOException if the current artifact is not a file or the process cannot start
    private static void startAgentProcess(String[] args) throws IOException {
        @Nullable URI codeSource = getCodeSource();
        if (codeSource == null || !"file".equalsIgnoreCase(codeSource.getScheme())) {
            throw new IOException("Plugin Mixins require a packaged HMCL JAR");
        }
        Path hmclArtifact = Path.of(codeSource).toAbsolutePath().normalize();
        if (!Files.isRegularFile(hmclArtifact)) {
            throw new IOException("Plugin Mixins require a packaged HMCL JAR: " + hmclArtifact);
        }

        String executableName = ProcessHandle.current().info().command()
                .map(Path::of)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.equalsIgnoreCase("javaw.exe") || name.equalsIgnoreCase("javaw"))
                .orElse(System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")
                        ? "java.exe"
                        : "java");
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", executableName);

        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        for (String inputArgument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!isTransientRuntimeArgument(inputArgument)) {
                command.add(inputArgument);
            }
        }
        command.add("-javaagent:" + hmclArtifact);
        command.add("-jar");
        command.add(hmclArtifact.toString());
        command.addAll(List.of(args));

        new ProcessBuilder(command)
                .directory(Path.of(System.getProperty("user.dir")).toFile())
                .inheritIO()
                .start();
    }

    /// Returns whether an input argument carries Mixin state that belongs only to the current JVM.
    ///
    /// These flags must not be copied into the Agent child because they can describe a stale
    /// process without proving that the child actually has instrumentation installed.
    ///
    /// @param inputArgument current JVM input argument
    /// @return whether the argument must be omitted from the Agent child
    private static boolean isTransientRuntimeArgument(String inputArgument) {
        if (!inputArgument.startsWith("-D")) {
            return false;
        }
        int equals = inputArgument.indexOf('=', 2);
        String propertyName = inputArgument.substring(2, equals >= 0 ? equals : inputArgument.length());
        return propertyName.equals(DISABLE_PROPERTY)
                || propertyName.equals(ACTIVE_PROPERTY)
                || propertyName.equals(AGENT_ACTIVE_PROPERTY);
    }

    /// Resolves the same local HMCL directory convention used by `Metadata` without loading launcher classes early.
    ///
    /// @return local HMCL home directory
    private static Path resolveLocalHome() {
        @Nullable String configured = System.getProperty("hmcl.dir", System.getenv("HMCL_LOCAL_HOME"));
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().resolve(".hmcl");
    }

    /// Discovers enabled Java and Kotlin packages, preparing a safe immutable launch cache for each one.
    ///
    /// @param localHome local HMCL directory
    /// @return enabled JVM plugin descriptors
    /// @throws IOException if state or package discovery fails
    private static @Unmodifiable List<PluginLaunchDescriptor> discoverEnabledJvmPlugins(Path localHome) throws IOException {
        Path pluginsDirectory = localHome.resolve("plugins");
        if (!Files.isDirectory(pluginsDirectory)) {
            return List.of();
        }

        PluginStates states = readPluginStates(localHome.resolve(PLUGIN_STATE_FILE));
        Set<String> enabled = copyValidIds(states.enabled);
        Set<String> pendingUninstall = copyValidIds(states.pendingUninstall);
        if (enabled.isEmpty()) {
            return List.of();
        }

        Path cacheRoot = localHome.resolve("plugin-cache");
        Files.createDirectories(cacheRoot);
        List<PluginLaunchDescriptor> descriptors = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        try (Stream<Path> files = Files.list(pluginsDirectory)) {
            for (Path nplFile : files
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".npl"))
                    .sorted()
                    .toList()) {
                try {
                    PluginManifestView manifest = readPluginManifest(nplFile);
                    @Nullable String id = manifest.id;
                    if (!isValidPluginId(id) || !enabled.contains(id) || pendingUninstall.contains(id)) {
                        continue;
                    }
                    if (!seenIds.add(id)) {
                        report("Ignoring duplicate enabled plugin ID " + id + " in " + nplFile.getFileName());
                        continue;
                    }
                    if (!isJvmPlugin(manifest.type)) {
                        continue;
                    }

                    Path cacheDirectory = preparePluginCache(nplFile, cacheRoot.resolve(id));
                    @Unmodifiable List<Path> pluginClassPath = collectPluginClassPath(cacheDirectory);
                    @Unmodifiable List<String> mixinConfigs = sanitizeMixinConfigs(manifest.mixins, id);
                    descriptors.add(new PluginLaunchDescriptor(id, pluginClassPath, mixinConfigs));
                } catch (IOException | RuntimeException exception) {
                    report("Skipping plugin package " + nplFile.getFileName() + " during Mixin discovery", exception);
                }
            }
        }

        return List.copyOf(descriptors);
    }

    /// Reads persisted plugin enablement state, treating absent or malformed state as empty.
    ///
    /// @param stateFile state file path
    /// @return parsed state object
    private static PluginStates readPluginStates(Path stateFile) {
        if (!Files.isRegularFile(stateFile)) {
            return new PluginStates();
        }

        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(stateFile), StandardCharsets.UTF_8)) {
            @Nullable PluginStates states = GSON.fromJson(reader, PluginStates.class);
            return states == null ? new PluginStates() : states;
        } catch (IOException | RuntimeException exception) {
            report("Unable to read plugin state before Mixin startup", exception);
            return new PluginStates();
        }
    }

    /// Copies valid, non-null plugin IDs from a deserialized list.
    ///
    /// @param ids deserialized ID list or `null`
    /// @return validated ID set
    private static Set<String> copyValidIds(@Nullable List<@Nullable String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (@Nullable String id : ids) {
            if (isValidPluginId(id)) {
                result.add(id);
            }
        }
        return result;
    }

    /// Reads a bounded `plugin.json` directly from an `.npl` archive.
    ///
    /// @param nplFile plugin package
    /// @return manifest projection needed during bootstrap
    /// @throws IOException if the archive or manifest is invalid
    private static PluginManifestView readPluginManifest(Path nplFile) throws IOException {
        try (ZipFile zipFile = new ZipFile(nplFile.toFile())) {
            @Nullable ZipEntry entry = zipFile.getEntry(PLUGIN_MANIFEST);
            if (entry == null || entry.isDirectory()) {
                throw new IOException("Missing " + PLUGIN_MANIFEST);
            }
            if (entry.getSize() > MAX_MANIFEST_BYTES) {
                throw new IOException("Plugin manifest is too large");
            }

            byte[] json;
            try (InputStream input = zipFile.getInputStream(entry)) {
                json = readBounded(input, MAX_MANIFEST_BYTES);
            }
            @Nullable PluginManifestView manifest = GSON.fromJson(
                    new String(json, StandardCharsets.UTF_8),
                    PluginManifestView.class
            );
            if (manifest == null) {
                throw new IOException("Empty plugin manifest");
            }
            return manifest;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid plugin manifest", exception);
        }
    }

    /// Reads an input stream while enforcing a maximum number of bytes.
    ///
    /// @param input source stream
    /// @param maximumBytes maximum accepted bytes
    /// @return complete stream contents
    /// @throws IOException if reading fails or the limit is exceeded
    private static byte[] readBounded(InputStream input, int maximumBytes) throws IOException {
        byte[] buffer = new byte[8192];
        int total = 0;
        List<byte[]> chunks = new ArrayList<>();
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            total = Math.addExact(total, read);
            if (total > maximumBytes) {
                throw new IOException("Input exceeds " + maximumBytes + " bytes");
            }
            byte[] chunk = new byte[read];
            System.arraycopy(buffer, 0, chunk, 0, read);
            chunks.add(chunk);
        }

        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }

    /// Returns whether a plugin type contributes JVM classes to the launch class path.
    ///
    /// @param type deserialized plugin type or `null`
    /// @return whether the type is Java or Kotlin
    private static boolean isJvmPlugin(@Nullable String type) {
        return type != null && (type.equalsIgnoreCase("java") || type.equalsIgnoreCase("kotlin"));
    }

    /// Returns whether a plugin ID is safe and structurally valid.
    ///
    /// @param id plugin ID or `null`
    /// @return whether the ID is valid
    private static boolean isValidPluginId(@Nullable String id) {
        return id != null && PLUGIN_ID_PATTERN.matcher(id).matches();
    }

    /// Validates and freezes Mixin configuration resource names declared by a plugin.
    ///
    /// @param configs deserialized configuration list or `null`
    /// @param pluginId owning plugin ID used in diagnostics
    /// @return validated configuration names
    private static @Unmodifiable List<String> sanitizeMixinConfigs(
            @Nullable List<@Nullable String> configs,
            String pluginId
    ) {
        if (configs == null || configs.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (@Nullable String candidate : configs) {
            if (candidate == null) {
                continue;
            }
            String config = candidate.trim();
            if (config.isEmpty()
                    || config.startsWith("/")
                    || config.contains("\\")
                    || config.contains(":")
                    || config.contains("../")
                    || !config.endsWith(".json")) {
                report("Ignoring unsafe Mixin configuration '" + candidate + "' from " + pluginId);
                continue;
            }
            result.add(config);
        }
        return List.copyOf(result);
    }

    /// Prepares an extracted cache whose contents match the source package hash.
    ///
    /// @param nplFile source plugin package
    /// @param cacheDirectory target cache directory
    /// @return ready cache directory
    /// @throws IOException if hashing, extraction, or replacement fails
    private static Path preparePluginCache(Path nplFile, Path cacheDirectory) throws IOException {
        String sourceHash = calculateSha256(nplFile);
        Path marker = cacheDirectory.resolve(".source.sha256");
        if (Files.isRegularFile(marker)
                && sourceHash.equalsIgnoreCase(Files.readString(marker, StandardCharsets.UTF_8).trim())
                && Files.isRegularFile(cacheDirectory.resolve(PLUGIN_MANIFEST))) {
            return cacheDirectory;
        }

        Path temporaryDirectory = cacheDirectory.resolveSibling(
                cacheDirectory.getFileName() + ".tmp-" + UUID.randomUUID()
        );
        deleteRecursively(temporaryDirectory);
        Files.createDirectories(temporaryDirectory);

        try {
            extractPlugin(nplFile, temporaryDirectory);
            Files.writeString(
                    temporaryDirectory.resolve(".source.sha256"),
                    sourceHash + System.lineSeparator(),
                    StandardCharsets.UTF_8
            );
            deleteRecursively(cacheDirectory);
            try {
                Files.move(temporaryDirectory, cacheDirectory, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryDirectory, cacheDirectory);
            }
            return cacheDirectory;
        } finally {
            deleteRecursively(temporaryDirectory);
        }
    }

    /// Extracts a plugin package with zip-slip, entry-count, and aggregate-size protection.
    ///
    /// @param nplFile source plugin package
    /// @param targetDirectory extraction directory
    /// @throws IOException if the package is unsafe or cannot be extracted
    private static void extractPlugin(Path nplFile, Path targetDirectory) throws IOException {
        int entryCount = 0;
        long totalBytes = 0;
        byte[] buffer = new byte[8192];

        try (ZipInputStream zipInput = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(nplFile)),
                StandardCharsets.UTF_8
        )) {
            @Nullable ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("Plugin package contains too many entries");
                }

                Path output = targetDirectory.resolve(entry.getName()).normalize();
                if (!output.startsWith(targetDirectory)) {
                    throw new IOException("Plugin package contains an unsafe path: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }

                @Nullable Path parent = output.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (BufferedOutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(output))) {
                    int read;
                    while ((read = zipInput.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        totalBytes = Math.addExact(totalBytes, read);
                        if (totalBytes > MAX_ARCHIVE_BYTES) {
                            throw new IOException("Plugin package expands beyond the allowed size");
                        }
                        outputStream.write(buffer, 0, read);
                    }
                }
            }
        } catch (ArithmeticException exception) {
            throw new IOException("Plugin package size overflow", exception);
        }
    }

    /// Calculates a lower-case SHA-256 digest for a plugin package.
    ///
    /// @param file file to hash
    /// @return hexadecimal digest
    /// @throws IOException if the file cannot be read or SHA-256 is unavailable
    private static String calculateSha256(Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }

        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }

        StringBuilder result = new StringBuilder(digest.getDigestLength() * 2);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    /// Collects the extracted plugin root and all nested JAR files in deterministic order.
    ///
    /// @param cacheDirectory extracted plugin directory
    /// @return plugin class path entries
    /// @throws IOException if traversal fails
    private static @Unmodifiable List<Path> collectPluginClassPath(Path cacheDirectory) throws IOException {
        List<Path> entries = new ArrayList<>();
        entries.add(cacheDirectory);
        try (Stream<Path> files = Files.walk(cacheDirectory)) {
            for (Path jar : files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().equals(HmclMixinAgent.ROOT_RESOURCE_JAR))
                    .sorted()
                    .toList()) {
                entries.add(jar);
            }
        }
        return List.copyOf(entries);
    }

    /// Collects globally unique Mixin configuration names and rejects ambiguous resources across plugins.
    ///
    /// @param descriptors enabled JVM plugin descriptors
    /// @return configuration names in deterministic plugin order
    /// @throws IOException if two plugins declare the same resource name
    private static @Unmodifiable List<String> collectMixinConfigs(
            @Unmodifiable List<PluginLaunchDescriptor> descriptors
    ) throws IOException {
        Map<String, String> owners = new java.util.LinkedHashMap<>();
        for (PluginLaunchDescriptor descriptor : descriptors) {
            for (String config : descriptor.mixinConfigs) {
                @Nullable String previousOwner = owners.putIfAbsent(config, descriptor.id);
                if (previousOwner != null) {
                    throw new IOException("Mixin configuration resource " + config
                            + " is declared by both " + previousOwner + " and " + descriptor.id);
                }
            }
        }
        return List.copyOf(owners.keySet());
    }

    /// Returns the code source containing the bootstrap class when available.
    ///
    /// @return bootstrap code source URI or `null`
    private static @Nullable URI getCodeSource() {
        try {
            if (HmclMixinBootstrap.class.getProtectionDomain() == null
                    || HmclMixinBootstrap.class.getProtectionDomain().getCodeSource() == null
                    || HmclMixinBootstrap.class.getProtectionDomain().getCodeSource().getLocation() == null) {
                return null;
            }
            return HmclMixinBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        } catch (Exception exception) {
            report("Unable to resolve the HMCL code source", exception);
            return null;
        }
    }

    /// Checks that a declared Mixin configuration exists in an extracted root or nested plugin JAR.
    ///
    /// @param descriptors enabled JVM plugin descriptors
    /// @param config resource name
    /// @return whether the resource exists
    private static boolean validateMixinResource(
            @Unmodifiable List<PluginLaunchDescriptor> descriptors,
            String config
    ) throws IOException {
        for (PluginLaunchDescriptor descriptor : descriptors) {
            for (Path entry : descriptor.classPath) {
                if (Files.isDirectory(entry) && Files.isRegularFile(entry.resolve(config))) {
                    return true;
                }
                if (Files.isRegularFile(entry)) {
                    try (ZipFile zipFile = new ZipFile(entry.toFile())) {
                        if (zipFile.getEntry(config) != null) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /// Deletes a directory tree when present.
    ///
    /// @param path path to delete
    /// @throws IOException if deletion fails
    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    /// Prints a bootstrap diagnostic before the regular logger is initialized.
    ///
    /// @param message diagnostic message
    private static void report(String message) {
        System.err.println("[HMCL Plugin Mixin] " + message);
    }

    /// Prints a bootstrap diagnostic and its underlying failure.
    ///
    /// @param message diagnostic message
    /// @param throwable underlying failure
    private static void report(String message, Throwable throwable) {
        report(message + ": " + throwable.getMessage());
        throwable.printStackTrace(System.err);
    }

    /// Minimal plugin manifest projection used before launcher classes are loaded.
    @NotNullByDefault
    private static final class PluginManifestView {
        /// Plugin identifier, or `null` when absent from malformed JSON.
        private @Nullable String id;

        /// Plugin implementation type, or `null` when absent.
        private @Nullable String type;

        /// Declared Mixin configuration resources, including possible malformed null elements.
        private @Nullable List<@Nullable String> mixins;

        /// Creates an empty projection for Gson deserialization.
        private PluginManifestView() {
        }
    }

    /// Minimal persisted state projection used before the regular plugin manager is loaded.
    @NotNullByDefault
    private static final class PluginStates {
        /// Enabled plugin IDs, or `null` when absent.
        private @Nullable List<@Nullable String> enabled;

        /// Plugin IDs awaiting uninstall, or `null` when absent.
        private @Nullable List<@Nullable String> pendingUninstall;

        /// Creates an empty state projection for missing files and Gson deserialization.
        private PluginStates() {
        }
    }

    /// Immutable launch-time metadata for one enabled JVM plugin.
    @NotNullByDefault
    private static final class PluginLaunchDescriptor {
        /// Plugin identifier used in diagnostics.
        private final String id;

        /// Extracted root and nested JAR paths appended to the system class loader by premain.
        private final @Unmodifiable List<Path> classPath;

        /// Validated Mixin configuration resource names.
        private final @Unmodifiable List<String> mixinConfigs;

        /// Creates an immutable plugin launch descriptor.
        ///
        /// @param id plugin identifier
        /// @param classPath plugin class path entries
        /// @param mixinConfigs Mixin configuration resources
        private PluginLaunchDescriptor(
                String id,
                @Unmodifiable List<Path> classPath,
                @Unmodifiable List<String> mixinConfigs
        ) {
            this.id = id;
            this.classPath = List.copyOf(classPath);
            this.mixinConfigs = List.copyOf(mixinConfigs);
        }
    }

    /// Immutable configuration transferred from package discovery to the premain agent.
    @NotNullByDefault
    static final class AgentConfiguration {
        /// Extracted roots and nested JARs appended to the system class loader.
        private final @Unmodifiable List<Path> classPathEntries;

        /// Globally unique Mixin configuration resources.
        private final @Unmodifiable List<String> mixinConfigs;

        /// Plugin IDs whose Mixin configurations are active in this process.
        private final @Unmodifiable List<String> activePluginIds;

        /// Creates an immutable agent configuration.
        ///
        /// @param classPathEntries plugin class path entries
        /// @param mixinConfigs Mixin configuration resources
        /// @param activePluginIds active Mixin plugin IDs
        private AgentConfiguration(
                @Unmodifiable List<Path> classPathEntries,
                @Unmodifiable List<String> mixinConfigs,
                @Unmodifiable List<String> activePluginIds
        ) {
            this.classPathEntries = List.copyOf(classPathEntries);
            this.mixinConfigs = List.copyOf(mixinConfigs);
            this.activePluginIds = List.copyOf(activePluginIds);
        }

        /// Returns plugin class path entries.
        ///
        /// @return class path entries
        @Unmodifiable List<Path> classPathEntries() {
            return classPathEntries;
        }

        /// Returns Mixin configuration resources.
        ///
        /// @return Mixin configurations
        @Unmodifiable List<String> mixinConfigs() {
            return mixinConfigs;
        }

        /// Returns active Mixin plugin IDs.
        ///
        /// @return active plugin IDs
        @Unmodifiable List<String> activePluginIds() {
            return activePluginIds;
        }
    }
}

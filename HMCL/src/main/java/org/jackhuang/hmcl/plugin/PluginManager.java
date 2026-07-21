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
package org.jackhuang.hmcl.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.plugin.internal.PluginPackageVersions;
import org.jackhuang.hmcl.plugin.loader.JavaPluginLoader;
import org.jackhuang.hmcl.plugin.loader.JavaScriptPluginLoader;
import org.jackhuang.hmcl.plugin.loader.PluginLoader;
import org.jackhuang.hmcl.plugin.mixin.bootstrap.HmclMixinBootstrap;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Discovers, validates, orders, loads, enables, disables, and removes HMCL plugins.
@NotNullByDefault
public final class PluginManager {
    /// Root manifest entry inside every plugin package.
    private static final String PLUGIN_MANIFEST = "plugin.json";

    /// Maximum uncompressed size accepted for `plugin.json`.
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;

    /// JSON codec used for persisted plugin state.
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /// Directory containing installed `.npl` files.
    private final Path pluginsDirectory;

    /// Directory containing extracted package contents used for normal lifecycle loading.
    private final Path pluginPackageDirectory;

    /// Directory containing persistent per-plugin private data.
    private final Path pluginStorageDirectory;

    /// Directory containing startup Mixin extraction caches.
    private final Path pluginMixinCacheDirectory;

    /// Persisted enablement and pending-uninstall state file.
    private final Path stateFile;

    /// Mutable observable list backing the plugin management UI.
    private final ObservableList<PluginContainer> plugins = FXCollections.observableArrayList();

    /// Loaded plugins indexed by validated plugin ID.
    private final Map<String, PluginContainer> pluginMap = new LinkedHashMap<>();

    /// Runtime loaders indexed by plugin implementation type.
    private final Map<PluginManifest.PluginType, PluginLoader> loaders = new EnumMap<>(PluginManifest.PluginType.class);

    /// Plugin IDs that should be enabled now or after the next Mixin-capable restart.
    private final Set<String> enabledStates = new HashSet<>();

    /// Plugin IDs whose files and data should be removed at the next startup.
    private final Set<String> pendingUninstall = new HashSet<>();

    /// Creates the singleton manager and its storage directories.
    private PluginManager() {
        this(Metadata.HMCL_LOCAL_HOME);
    }

    /// Creates an isolated manager rooted at the supplied HMCL home.
    ///
    /// This constructor is package-private so lifecycle and installation behavior can be tested without
    /// mutating the user's launcher directory.
    ///
    /// @param localHome isolated HMCL home
    PluginManager(Path localHome) {
        pluginsDirectory = localHome.resolve("plugins");
        pluginPackageDirectory = localHome.resolve("plugin-data");
        pluginStorageDirectory = localHome.resolve("plugin-storage");
        pluginMixinCacheDirectory = localHome.resolve("plugin-cache");
        stateFile = localHome.resolve("plugin-states.json");

        try {
            Files.createDirectories(pluginsDirectory);
            Files.createDirectories(pluginPackageDirectory);
            Files.createDirectories(pluginStorageDirectory);
            Files.createDirectories(pluginMixinCacheDirectory);
        } catch (IOException exception) {
            LOG.error("Failed to create plugin directories", exception);
        }

        loadStates();
        loaders.put(PluginManifest.PluginType.JAVA, new JavaPluginLoader());
        loaders.put(PluginManifest.PluginType.KOTLIN, new JavaPluginLoader());
        loaders.put(PluginManifest.PluginType.JAVASCRIPT, new JavaScriptPluginLoader());
    }

    /// Returns the process-wide plugin manager.
    ///
    /// @return plugin manager singleton
    public static PluginManager getInstance() {
        return Holder.INSTANCE;
    }

    /// Loads persisted enablement and uninstall state, discarding malformed null IDs.
    private void loadStates() {
        if (!Files.isRegularFile(stateFile)) {
            return;
        }
        try {
            @Nullable PluginStates states = GSON.fromJson(
                    Files.readString(stateFile, StandardCharsets.UTF_8),
                    PluginStates.class
            );
            if (states != null) {
                copyNonNull(states.enabled, enabledStates);
                copyNonNull(states.pendingUninstall, pendingUninstall);
            }
        } catch (IOException | RuntimeException exception) {
            LOG.warning("Failed to load plugin states", exception);
        }
    }

    /// Copies non-null strings from a deserialized list into a mutable set.
    ///
    /// @param source deserialized values or `null`
    /// @param target destination set
    private static void copyNonNull(@Nullable List<@Nullable String> source, Set<String> target) {
        if (source == null) {
            return;
        }
        for (@Nullable String value : source) {
            if (value != null && PluginManifest.isValidId(value)) {
                target.add(value);
            }
        }
    }

    /// Persists plugin state through an atomic replacement when supported by the file system.
    private void saveStates() {
        PluginStates states = new PluginStates();
        states.enabled = enabledStates.stream().sorted().toList();
        states.pendingUninstall = pendingUninstall.stream().sorted().toList();

        Path temporaryFile = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(stateFile.getParent());
            Files.writeString(temporaryFile, GSON.toJson(states), StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporaryFile,
                        stateFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            LOG.warning("Failed to save plugin states", exception);
        } finally {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException exception) {
                LOG.warning("Failed to delete temporary plugin state file", exception);
            }
        }
    }

    /// Discovers packages, applies pending removals, loads dependencies first, and restores enablement state.
    public void discoverPlugins() {
        LOG.info("Discovering plugins...");
        try {
            Map<String, PluginCandidate> candidates = readCandidates();
            applyPendingUninstalls(candidates);

            Map<String, VisitState> visitStates = new HashMap<>();
            List<PluginContainer> loadOrder = new ArrayList<>();
            Set<String> failed = new HashSet<>();
            for (PluginCandidate candidate : candidates.values()) {
                loadCandidate(candidate, candidates, visitStates, failed, loadOrder);
            }

            for (PluginContainer container : loadOrder) {
                String pluginId = container.getManifest().getId();
                if (enabledStates.contains(pluginId)) {
                    enablePlugin(pluginId);
                }
            }
            LOG.info("Discovered " + plugins.size() + " plugin(s)");
        } catch (IOException exception) {
            LOG.error("Failed to discover plugins", exception);
        }
    }

    /// Reads and validates every package manifest, rejecting duplicate IDs deterministically.
    ///
    /// @return package candidates indexed by ID
    /// @throws IOException if the plugin directory cannot be listed
    private Map<String, PluginCandidate> readCandidates() throws IOException {
        Map<String, PluginCandidate> candidates = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(pluginsDirectory)) {
            for (Path nplFile : files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".npl"))
                    .sorted()
                    .toList()) {
                try {
                    PluginManifest manifest = readManifest(nplFile);
                    PluginCandidate previous = candidates.putIfAbsent(
                            manifest.getId(),
                            new PluginCandidate(nplFile, manifest)
                    );
                    if (previous != null) {
                        LOG.error("Duplicate plugin ID " + manifest.getId() + " in "
                                + previous.nplFile.getFileName() + " and " + nplFile.getFileName());
                    }
                } catch (IOException | RuntimeException exception) {
                    LOG.error("Invalid plugin package: " + nplFile.getFileName(), exception);
                }
            }
        }
        return candidates;
    }

    /// Removes packages and data marked for uninstall before any plugin classes are loaded.
    ///
    /// @param candidates mutable package candidates
    private void applyPendingUninstalls(Map<String, PluginCandidate> candidates) {
        boolean stateChanged = false;
        for (String pluginId : List.copyOf(pendingUninstall)) {
            @Nullable PluginCandidate candidate = candidates.remove(pluginId);
            try {
                if (candidate != null) {
                    Files.deleteIfExists(candidate.nplFile);
                }
                deletePluginDirectories(pluginId);
                pendingUninstall.remove(pluginId);
                enabledStates.remove(pluginId);
                stateChanged = true;
                LOG.info("Uninstalled plugin marked for removal: " + pluginId);
            } catch (IOException exception) {
                LOG.warning("Failed to complete pending plugin uninstall: " + pluginId, exception);
            }
        }
        if (stateChanged) {
            saveStates();
        }
    }

    /// Loads a candidate after recursively loading all declared dependencies.
    ///
    /// @param candidate candidate to load
    /// @param candidates available candidates
    /// @param visitStates dependency traversal states
    /// @param failed plugin IDs that cannot be loaded
    /// @param loadOrder successfully loaded containers in dependency order
    /// @return whether the candidate loaded successfully
    private boolean loadCandidate(
            PluginCandidate candidate,
            Map<String, PluginCandidate> candidates,
            Map<String, VisitState> visitStates,
            Set<String> failed,
            List<PluginContainer> loadOrder
    ) {
        String pluginId = candidate.manifest.getId();
        if (pluginMap.containsKey(pluginId)) {
            return true;
        }
        if (failed.contains(pluginId)) {
            return false;
        }

        @Nullable VisitState state = visitStates.get(pluginId);
        if (state == VisitState.VISITING) {
            LOG.error("Cyclic plugin dependency detected at " + pluginId);
            failed.add(pluginId);
            return false;
        }
        if (state == VisitState.VISITED) {
            return !failed.contains(pluginId);
        }

        visitStates.put(pluginId, VisitState.VISITING);
        for (String dependencyId : candidate.manifest.getDependencies()) {
            @Nullable PluginCandidate dependency = candidates.get(dependencyId);
            if (dependency == null && !pluginMap.containsKey(dependencyId)) {
                LOG.error("Plugin " + pluginId + " requires missing dependency " + dependencyId);
                failed.add(pluginId);
                visitStates.put(pluginId, VisitState.VISITED);
                return false;
            }
            if (dependency != null
                    && !loadCandidate(dependency, candidates, visitStates, failed, loadOrder)) {
                LOG.error("Plugin " + pluginId + " cannot load because dependency " + dependencyId + " failed");
                failed.add(pluginId);
                visitStates.put(pluginId, VisitState.VISITED);
                return false;
            }
        }

        try {
            PluginContainer container = loadPlugin(candidate.nplFile);
            loadOrder.add(container);
        } catch (IOException | RuntimeException exception) {
            failed.add(pluginId);
            LOG.error("Failed to load plugin: " + candidate.nplFile.getFileName(), exception);
        }
        visitStates.put(pluginId, VisitState.VISITED);
        return !failed.contains(pluginId);
    }

    /// Reads and validates a bounded manifest directly from a plugin package.
    ///
    /// @param nplFile plugin package path
    /// @return validated manifest
    /// @throws IOException if the package or manifest is invalid
    private PluginManifest readManifest(Path nplFile) throws IOException {
        try (ZipFile zipFile = new ZipFile(nplFile.toFile())) {
            @Nullable ZipEntry manifestEntry = zipFile.getEntry(PLUGIN_MANIFEST);
            if (manifestEntry == null || manifestEntry.isDirectory()) {
                throw new IOException(PLUGIN_MANIFEST + " not found in " + nplFile.getFileName());
            }
            if (manifestEntry.getSize() > MAX_MANIFEST_BYTES) {
                throw new IOException("Plugin manifest is too large: " + nplFile.getFileName());
            }
            byte[] bytes;
            try (InputStream input = zipFile.getInputStream(manifestEntry)) {
                bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
            }
            if (bytes.length > MAX_MANIFEST_BYTES) {
                throw new IOException("Plugin manifest is too large: " + nplFile.getFileName());
            }
            return PluginManifest.fromJson(new java.io.StringReader(new String(bytes, StandardCharsets.UTF_8)));
        }
    }

    /// Extracts, loads, registers, and invokes `onLoad` for a plugin package.
    ///
    /// This method mutates the observable plugin list and must run on the JavaFX thread.
    ///
    /// @param nplFile installed package path
    /// @return registered plugin container
    /// @throws IOException if preparation or registration fails
    public PluginContainer loadPlugin(Path nplFile) throws IOException {
        return registerPreparedPlugin(preparePluginInternal(nplFile));
    }

    /// Performs package validation, compatibility checks, safe extraction, and lifecycle class loading.
    ///
    /// @param nplFile installed package path
    /// @return prepared plugin value
    /// @throws IOException if preparation fails
    private PreparedPlugin preparePluginInternal(Path nplFile) throws IOException {
        LOG.info("Preparing plugin: " + nplFile.getFileName());
        PluginManifest manifest = readManifest(nplFile);
        String pluginId = manifest.getId();

        if (pluginMap.containsKey(pluginId)) {
            throw new IOException("Plugin already loaded: " + pluginId);
        }
        if (!isLauncherCompatible(manifest.getMinLauncherVersion())) {
            throw new IOException("Plugin " + pluginId + " requires HMCL "
                    + manifest.getMinLauncherVersion() + " or newer");
        }
        for (String dependencyId : manifest.getDependencies()) {
            if (!pluginMap.containsKey(dependencyId)) {
                throw new IOException("Plugin " + pluginId + " requires loaded dependency " + dependencyId);
            }
        }

        Path packageDirectory = PluginPackageVersions.prepareLifecyclePackage(
                nplFile,
                pluginPackageDirectory,
                pluginId
        );
        Path dataDirectory = pluginStorageDirectory.resolve(pluginId);
        Files.createDirectories(dataDirectory);

        @Nullable PluginLoader loader = loaders.get(manifest.getType());
        if (loader == null) {
            throw new IOException("No loader found for plugin type: " + manifest.getType());
        }

        Plugin plugin = loader.load(manifest, packageDirectory, nplFile);
        ClassLoader classLoader = plugin.getClass().getClassLoader();
        for (String mixinConfig : manifest.getMixins()) {
            if (classLoader.getResource(mixinConfig) == null) {
                closeLoaderAfterFailure(plugin, classLoader);
                throw new IOException("Mixin configuration resource not found: " + mixinConfig);
            }
        }

        PluginContext context = new PluginContext(manifest, packageDirectory, dataDirectory, classLoader);
        return new PreparedPlugin(plugin, context, manifest, nplFile);
    }

    /// Returns whether the current launcher version satisfies a non-empty minimum version.
    ///
    /// Development snapshots are treated as compatible with their current major line.
    ///
    /// @param minimumVersion minimum version or an empty string
    /// @return whether the launcher is compatible
    private static boolean isLauncherCompatible(String minimumVersion) {
        if (minimumVersion.isBlank()) {
            return true;
        }
        String current = Metadata.VERSION.toLowerCase(Locale.ROOT);
        if (current.contains("snapshot") || current.contains("develop")) {
            return true;
        }
        return PluginVersion.compare(Metadata.VERSION, minimumVersion) >= 0;
    }

    /// Closes a dedicated plugin loader after preparation fails.
    ///
    /// @param plugin partially loaded plugin instance
    /// @param classLoader class loader that defined the plugin
    private static void closeLoaderAfterFailure(Plugin plugin, ClassLoader classLoader) {
        try {
            plugin.onUnload();
        } catch (RuntimeException exception) {
            LOG.warning("Plugin cleanup failed after preparation error", exception);
        }
        if (classLoader != PluginManager.class.getClassLoader()
                && classLoader instanceof java.net.URLClassLoader urlClassLoader) {
            try {
                urlClassLoader.close();
            } catch (IOException exception) {
                LOG.warning("Failed to close plugin class loader after preparation error", exception);
            }
        }
    }

    /// Prepares a plugin on a background thread before JavaFX registration.
    ///
    /// @param nplFile installed package path
    /// @return prepared plugin
    /// @throws IOException if preparation fails
    public PreparedPlugin preparePlugin(Path nplFile) throws IOException {
        return preparePluginInternal(nplFile);
    }

    /// Registers a prepared plugin and invokes `onLoad` on the JavaFX thread.
    ///
    /// @param prepared prepared plugin value
    /// @return registered container
    public PluginContainer registerPreparedPlugin(PreparedPlugin prepared) {
        String pluginId = prepared.manifest.getId();
        if (pluginMap.containsKey(pluginId)) {
            throw new IllegalStateException("Plugin already loaded: " + pluginId);
        }

        PluginContainer container = new PluginContainer(prepared.plugin, prepared.context, prepared.nplFile);
        plugins.add(container);
        pluginMap.put(pluginId, container);
        try {
            prepared.plugin.onLoad(prepared.context);
            LOG.info("Loaded plugin: " + prepared.manifest.getName() + " v" + prepared.manifest.getVersion());
            return container;
        } catch (RuntimeException | Error exception) {
            plugins.remove(container);
            pluginMap.remove(pluginId);
            try {
                container.closeClassLoader();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Enables a plugin and its dependencies, or records a restart-pending Mixin enablement.
    ///
    /// @param pluginId plugin ID
    public void enablePlugin(String pluginId) {
        enablePlugin(pluginId, new HashSet<>());
        saveStates();
    }

    /// Recursively enables one plugin while detecting unexpected runtime dependency cycles.
    ///
    /// @param pluginId plugin ID
    /// @param visiting IDs in the current enable traversal
    /// @return whether the lifecycle is active now
    private boolean enablePlugin(String pluginId, Set<String> visiting) {
        @Nullable PluginContainer container = pluginMap.get(pluginId);
        if (container == null) {
            LOG.error("Cannot enable missing plugin: " + pluginId);
            return false;
        }
        if (container.isEnabled()) {
            return true;
        }
        if (!visiting.add(pluginId)) {
            LOG.error("Cyclic plugin enablement detected at " + pluginId);
            return false;
        }

        for (String dependencyId : container.getManifest().getDependencies()) {
            if (!enablePlugin(dependencyId, visiting)) {
                enabledStates.add(pluginId);
                container.setRestartRequired(true);
                visiting.remove(pluginId);
                return false;
            }
        }

        if (container.getManifest().hasMixins() && !isMixinActive(pluginId)) {
            enabledStates.add(pluginId);
            container.setRestartRequired(true);
            LOG.info("Plugin " + pluginId + " will enable after restart so its Mixins can be applied");
            visiting.remove(pluginId);
            return false;
        }

        try {
            container.getPlugin().onEnable();
            container.setEnabled(true);
            container.setRestartRequired(false);
            enabledStates.add(pluginId);
            LOG.info("Enabled plugin: " + pluginId);
            visiting.remove(pluginId);
            return true;
        } catch (RuntimeException exception) {
            LOG.error("Failed to enable plugin: " + pluginId, exception);
            visiting.remove(pluginId);
            return false;
        }
    }

    /// Disables dependents first, then disables the requested plugin.
    ///
    /// Active Mixin bytecode remains until restart and is reflected by `restartRequired`.
    ///
    /// @param pluginId plugin ID
    public void disablePlugin(String pluginId) {
        for (PluginContainer dependent : List.copyOf(plugins)) {
            if (dependent.isEnabled() && dependent.getManifest().getDependencies().contains(pluginId)) {
                disablePlugin(dependent.getManifest().getId());
            }
        }

        @Nullable PluginContainer container = pluginMap.get(pluginId);
        if (container == null) {
            return;
        }
        if (container.isEnabled()) {
            try {
                container.getPlugin().onDisable();
                container.setEnabled(false);
                PluginUIRegistry.unregisterAll(pluginId);
                LOG.info("Disabled plugin: " + pluginId);
            } catch (RuntimeException exception) {
                LOG.error("Failed to disable plugin: " + pluginId, exception);
                return;
            }
        }

        enabledStates.remove(pluginId);
        container.setRestartRequired(container.getManifest().hasMixins() && isMixinActive(pluginId));
        saveStates();
    }

    /// Unloads dependents first, invokes lifecycle cleanup, and closes a dedicated class loader.
    ///
    /// @param pluginId plugin ID
    public void unloadPlugin(String pluginId) {
        for (PluginContainer dependent : List.copyOf(plugins)) {
            if (dependent.getManifest().getDependencies().contains(pluginId)) {
                unloadPlugin(dependent.getManifest().getId());
            }
        }

        @Nullable PluginContainer container = pluginMap.get(pluginId);
        if (container == null) {
            return;
        }
        if (container.isEnabled()) {
            disablePlugin(pluginId);
        }

        try {
            container.getPlugin().onUnload();
        } catch (RuntimeException exception) {
            LOG.warning("Plugin onUnload failed: " + pluginId, exception);
        } finally {
            plugins.remove(container);
            pluginMap.remove(pluginId);
            PluginUIRegistry.unregisterAll(pluginId);
            try {
                container.closeClassLoader();
            } catch (IOException exception) {
                LOG.warning("Failed to close plugin class loader: " + pluginId, exception);
            }
        }
        LOG.info("Unloaded plugin: " + pluginId);
    }

    /// Validates and prepares a user-selected local plugin package for installation.
    ///
    /// New plugin IDs are copied into the plugin directory and prepared for JavaFX registration. If the same
    /// plugin ID is already installed or loaded, the replacement is staged for the next restart instead; the
    /// currently loaded classes are never registered a second time.
    ///
    /// @param sourcePackage user-selected `.npl` package
    /// @return prepared new installation or restart-staged update
    /// @throws IOException if validation, copying, preparation, or staging fails
    public LocalPluginInstallation prepareLocalPluginInstallation(Path sourcePackage) throws IOException {
        Path source = sourcePackage.toAbsolutePath().normalize();
        validateLocalPluginPackage(source);

        PluginManifest manifest = readManifest(source);
        String pluginId = manifest.getId();
        if (!isLauncherCompatible(manifest.getMinLauncherVersion())) {
            throw new IOException("Plugin " + pluginId + " requires HMCL "
                    + manifest.getMinLauncherVersion() + " or newer");
        }

        boolean restartUpdate = pluginMap.containsKey(pluginId)
                || !findInstalledPackages(pluginId).isEmpty();
        Path installedPackage = source;
        boolean copied = false;
        if (!Objects.equals(source.getParent(), pluginsDirectory.toAbsolutePath().normalize())) {
            installedPackage = copyLocalPluginPackage(source, manifest, restartUpdate);
            copied = true;
        }

        try {
            if (restartUpdate) {
                stagePluginUpdate(pluginId, installedPackage);
                return LocalPluginInstallation.staged(manifest);
            }
            return LocalPluginInstallation.prepared(preparePluginInternal(installedPackage));
        } catch (IOException | RuntimeException | Error exception) {
            if (copied) {
                try {
                    Files.deleteIfExists(installedPackage);
                } catch (IOException cleanupException) {
                    exception.addSuppressed(cleanupException);
                }
            }
            throw exception;
        }
    }

    /// Rejects missing, unreadable, non-regular, or incorrectly named local packages.
    ///
    /// @param packageFile candidate local package
    /// @throws IOException if the package cannot be safely consumed
    private static void validateLocalPluginPackage(Path packageFile) throws IOException {
        @Nullable Path fileName = packageFile.getFileName();
        if (fileName == null
                || !fileName.toString().toLowerCase(Locale.ROOT).endsWith(".npl")
                || !Files.isRegularFile(packageFile)
                || !Files.isReadable(packageFile)) {
            throw new IOException("Plugin package is not a readable .npl file: " + packageFile);
        }
    }

    /// Copies an external local package into the plugin directory without overwriting any live package.
    ///
    /// Updates always use a unique path so validation or staging failure leaves the old package untouched.
    /// New installations use the stable `<plugin-id>.npl` name when it is available.
    ///
    /// @param source validated source package
    /// @param manifest validated source manifest
    /// @param restartUpdate whether an existing package will be replaced at restart
    /// @return copied package path inside the plugin directory
    /// @throws IOException if copying, validation, or atomic publication fails
    private Path copyLocalPluginPackage(
            Path source,
            PluginManifest manifest,
            boolean restartUpdate
    ) throws IOException {
        String pluginId = manifest.getId();
        Path target = pluginsDirectory.resolve(pluginId + ".npl");
        if (restartUpdate || Files.exists(target)) {
            target = pluginsDirectory.resolve(pluginId + "-" + UUID.randomUUID() + ".npl");
        }

        Path temporaryFile = pluginsDirectory.resolve(
                "." + pluginId + "-" + UUID.randomUUID() + ".installing"
        );
        try {
            Files.copy(source, temporaryFile);
            PluginManifest copiedManifest = readManifest(temporaryFile);
            if (!pluginId.equals(copiedManifest.getId())) {
                throw new IOException("Plugin package changed while it was being copied: " + source);
            }
            if (!isLauncherCompatible(copiedManifest.getMinLauncherVersion())) {
                throw new IOException("Plugin " + pluginId + " requires HMCL "
                        + copiedManifest.getMinLauncherVersion() + " or newer");
            }
            try {
                Files.move(temporaryFile, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, target);
            }
            return target;
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    /// Finds readable installed packages that declare the supplied plugin ID.
    ///
    /// @param pluginId validated plugin ID
    /// @return sorted matching paths
    /// @throws IOException if the installed plugin directory cannot be listed
    private List<Path> findInstalledPackages(String pluginId) throws IOException {
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> files = Files.list(pluginsDirectory)) {
            for (Path packageFile : files
                    .filter(Files::isRegularFile)
                    .filter(Files::isReadable)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".npl"))
                    .sorted()
                    .toList()) {
                try {
                    if (pluginId.equals(readManifest(packageFile).getId())) {
                        matches.add(packageFile.toAbsolutePath().normalize());
                    }
                } catch (IOException | RuntimeException ignored) {
                    // Invalid packages are handled by normal discovery and must not block a local install.
                }
            }
        }
        return matches;
    }

    /// Copies, loads, and enables a local plugin package.
    ///
    /// @param nplFile source package
    /// @return installed container
    /// @throws IOException if copying or loading fails
    public PluginContainer installPlugin(Path nplFile) throws IOException {
        Path targetPath = pluginsDirectory.resolve(nplFile.getFileName());
        if (!nplFile.toAbsolutePath().normalize().equals(targetPath.toAbsolutePath().normalize())) {
            Files.copy(nplFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
        PluginContainer container = loadPlugin(targetPath);
        enablePlugin(container.getManifest().getId());
        return container;
    }

    /// Validates a replacement package and makes it the installed package for the next restart.
    ///
    /// The currently loaded classes remain untouched; this is used to update an active Mixin plugin safely.
    ///
    /// @param pluginId expected plugin ID
    /// @param replacementPackage downloaded replacement package already stored in the plugin directory
    /// @throws IOException if the replacement is invalid or file replacement fails
    public void stagePluginUpdate(String pluginId, Path replacementPackage) throws IOException {
        Path replacement = replacementPackage.toAbsolutePath().normalize();
        Path pluginRoot = pluginsDirectory.toAbsolutePath().normalize();
        validateLocalPluginPackage(replacement);
        if (!Objects.equals(replacement.getParent(), pluginRoot)) {
            throw new IOException("Staged plugin package must be stored directly in " + pluginRoot);
        }

        PluginManifest replacementManifest = readManifest(replacement);
        if (!pluginId.equals(replacementManifest.getId())) {
            throw new IOException("Downloaded package ID " + replacementManifest.getId()
                    + " does not match store entry " + pluginId);
        }
        if (!isLauncherCompatible(replacementManifest.getMinLauncherVersion())) {
            throw new IOException("Plugin " + pluginId + " requires HMCL "
                    + replacementManifest.getMinLauncherVersion() + " or newer");
        }

        for (Path installedPackage : findInstalledPackages(pluginId)) {
            if (!installedPackage.equals(replacement)) {
                Files.deleteIfExists(installedPackage);
            }
        }

        @Nullable PluginContainer container = pluginMap.get(pluginId);
        if (container != null) {
            Path oldPackage = container.getNplFile().toAbsolutePath().normalize();
            if (!oldPackage.equals(replacement)) {
                Files.deleteIfExists(oldPackage);
            }
            container.setNplFile(replacement);
            container.setRestartRequired(true);
        }
        if (pendingUninstall.remove(pluginId)) {
            saveStates();
        }
        LOG.info("Staged plugin update for next restart: " + pluginId);
    }

    /// Uninstalls a plugin immediately when safe, otherwise marks it for restart-time removal.
    ///
    /// @param pluginId plugin ID
    /// @throws IOException if package or directory deletion fails
    public void uninstallPlugin(String pluginId) throws IOException {
        @Nullable PluginContainer container = pluginMap.get(pluginId);
        if (container == null) {
            return;
        }
        if (requiresRestartForUninstall(pluginId)) {
            markForUninstall(pluginId);
            return;
        }

        Path nplFile = container.getNplFile();
        unloadPlugin(pluginId);
        Files.deleteIfExists(nplFile);
        deletePluginDirectories(pluginId);
        enabledStates.remove(pluginId);
        pendingUninstall.remove(pluginId);
        saveStates();
        LOG.info("Uninstalled plugin: " + pluginId);
    }

    /// Returns whether uninstalling the plugin must wait until a restart.
    ///
    /// @param pluginId plugin ID
    /// @return whether restart-time removal is required
    public boolean requiresRestartForUninstall(String pluginId) {
        @Nullable PluginContainer container = pluginMap.get(pluginId);
        return container != null
                && (container.isEnabled()
                || container.getManifest().hasMixins() && isMixinActive(pluginId));
    }

    /// Marks a plugin for removal before the next Mixin bootstrap and launcher load.
    ///
    /// @param pluginId plugin ID
    public void markForUninstall(String pluginId) {
        @Nullable PluginContainer container = pluginMap.get(pluginId);
        if (container != null && container.isEnabled()) {
            disablePlugin(pluginId);
        }
        pendingUninstall.add(pluginId);
        enabledStates.remove(pluginId);
        if (container != null) {
            container.setRestartRequired(true);
        }
        saveStates();
        LOG.info("Marked plugin for uninstall on next restart: " + pluginId);
    }

    /// Returns whether a plugin is marked for restart-time removal.
    ///
    /// @param pluginId plugin ID
    /// @return pending-uninstall state
    public boolean isMarkedForUninstall(String pluginId) {
        return pendingUninstall.contains(pluginId);
    }

    /// Returns whether a plugin's Mixin configurations were registered before this launcher instance loaded.
    ///
    /// @param pluginId plugin ID
    /// @return whether the plugin's Mixins are active
    public boolean isMixinActive(String pluginId) {
        @Nullable String active = System.getProperty(HmclMixinBootstrap.ACTIVE_PROPERTY);
        if (active == null || active.isBlank()) {
            return false;
        }
        for (String activeId : active.split(",")) {
            if (pluginId.equals(activeId)) {
                return true;
            }
        }
        return false;
    }

    /// Returns an unmodifiable observable view of loaded plugins.
    ///
    /// @return loaded plugin view
    public @UnmodifiableView ObservableList<PluginContainer> getPlugins() {
        return FXCollections.unmodifiableObservableList(plugins);
    }

    /// Returns a loaded plugin by ID.
    ///
    /// @param pluginId plugin ID
    /// @return loaded container or `null`
    public @Nullable PluginContainer getPlugin(String pluginId) {
        return pluginMap.get(pluginId);
    }

    /// Returns the installed package directory.
    ///
    /// @return plugin package directory
    public Path getPluginsDirectory() {
        return pluginsDirectory;
    }

    /// Deletes extracted package, persistent storage, and Mixin cache directories for one plugin ID.
    ///
    /// @param pluginId plugin ID
    /// @throws IOException if deletion fails
    private void deletePluginDirectories(String pluginId) throws IOException {
        FileUtils.deleteDirectory(pluginPackageDirectory.resolve(pluginId));
        FileUtils.deleteDirectory(PluginPackageVersions.getPluginVersionsDirectory(
                pluginPackageDirectory,
                pluginId
        ));
        FileUtils.deleteDirectory(pluginStorageDirectory.resolve(pluginId));
        FileUtils.deleteDirectory(pluginMixinCacheDirectory.resolve(pluginId));
        FileUtils.deleteDirectory(PluginPackageVersions.getPluginVersionsDirectory(
                pluginMixinCacheDirectory,
                pluginId
        ));
    }

    /// Lazily initialized singleton holder.
    @NotNullByDefault
    private static final class Holder {
        /// Plugin manager singleton.
        private static final PluginManager INSTANCE = new PluginManager();

        /// Prevents construction of the holder.
        private Holder() {
        }
    }

    /// JSON representation persisted in `plugin-states.json`.
    @NotNullByDefault
    private static final class PluginStates {
        /// IDs requested to be enabled, or `null` in malformed legacy files.
        private @Nullable List<@Nullable String> enabled;

        /// IDs awaiting uninstall, or `null` in malformed legacy files.
        private @Nullable List<@Nullable String> pendingUninstall;

        /// Creates an empty state object for Gson and saving.
        private PluginStates() {
        }
    }

    /// Package path and validated manifest discovered before dependency traversal.
    @NotNullByDefault
    private static final class PluginCandidate {
        /// Installed package path.
        private final Path nplFile;

        /// Validated package manifest.
        private final PluginManifest manifest;

        /// Creates a package candidate.
        ///
        /// @param nplFile installed package path
        /// @param manifest validated manifest
        private PluginCandidate(Path nplFile, PluginManifest manifest) {
            this.nplFile = nplFile;
            this.manifest = manifest;
        }
    }

    /// Dependency traversal state for one plugin ID.
    @NotNullByDefault
    private enum VisitState {
        /// Candidate is currently being traversed.
        VISITING,

        /// Candidate traversal has completed successfully or unsuccessfully.
        VISITED
    }

    /// Describes the background portion of a user-selected local plugin installation.
    @NotNullByDefault
    public static final class LocalPluginInstallation {
        /// Validated package manifest.
        private final PluginManifest manifest;

        /// Prepared lifecycle value for a new plugin, or `null` for a restart-staged update.
        private final @Nullable PreparedPlugin preparedPlugin;

        /// Creates a local installation result.
        ///
        /// @param manifest validated package manifest
        /// @param preparedPlugin prepared new plugin or `null` for an update
        private LocalPluginInstallation(
                PluginManifest manifest,
                @Nullable PreparedPlugin preparedPlugin
        ) {
            this.manifest = manifest;
            this.preparedPlugin = preparedPlugin;
        }

        /// Creates a result for a new plugin that is ready for JavaFX registration.
        ///
        /// @param preparedPlugin prepared plugin
        /// @return prepared installation result
        private static LocalPluginInstallation prepared(PreparedPlugin preparedPlugin) {
            return new LocalPluginInstallation(preparedPlugin.getManifest(), preparedPlugin);
        }

        /// Creates a result for an existing plugin whose package is staged for restart.
        ///
        /// @param manifest replacement package manifest
        /// @return restart-staged update result
        private static LocalPluginInstallation staged(PluginManifest manifest) {
            return new LocalPluginInstallation(manifest, null);
        }

        /// Returns the validated replacement or installation manifest.
        ///
        /// @return package manifest
        public PluginManifest getManifest() {
            return manifest;
        }

        /// Returns whether this update must wait for a launcher restart.
        ///
        /// @return whether no runtime registration should be attempted
        public boolean isRestartRequired() {
            return preparedPlugin == null;
        }

        /// Returns the prepared new plugin for JavaFX registration.
        ///
        /// @return prepared plugin
        /// @throws IllegalStateException if this result represents a restart-staged update
        public PreparedPlugin getPreparedPlugin() {
            if (preparedPlugin == null) {
                throw new IllegalStateException("Plugin update is staged for restart");
            }
            return preparedPlugin;
        }
    }

    /// Holds a package whose I/O preparation is complete but whose JavaFX lifecycle registration is pending.
    @NotNullByDefault
    public static final class PreparedPlugin {
        /// Loaded lifecycle implementation.
        private final Plugin plugin;

        /// Context prepared for the lifecycle implementation.
        private final PluginContext context;

        /// Validated package manifest.
        private final PluginManifest manifest;

        /// Installed package path.
        private final Path nplFile;

        /// Creates a prepared plugin value.
        ///
        /// @param plugin lifecycle implementation
        /// @param context plugin context
        /// @param manifest validated manifest
        /// @param nplFile installed package path
        private PreparedPlugin(Plugin plugin, PluginContext context, PluginManifest manifest, Path nplFile) {
            this.plugin = plugin;
            this.context = context;
            this.manifest = manifest;
            this.nplFile = nplFile;
        }

        /// Returns the validated manifest for installation UI decisions.
        ///
        /// @return plugin manifest
        public PluginManifest getManifest() {
            return manifest;
        }
    }
}

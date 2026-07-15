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
import org.jackhuang.hmcl.plugin.loader.JavaPluginLoader;
import org.jackhuang.hmcl.plugin.loader.JavaScriptPluginLoader;
import org.jackhuang.hmcl.plugin.loader.PluginLoader;
import org.jackhuang.hmcl.util.io.FileUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Central plugin manager for HMCL.
 */
public final class PluginManager {

    private static PluginManager instance;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path pluginsDirectory;
    private final Path pluginDataDirectory;
    private final Path stateFile;
    private final ObservableList<PluginContainer> plugins = FXCollections.observableArrayList();
    private final Map<String, PluginContainer> pluginMap = new HashMap<>();
    private final Map<PluginManifest.PluginType, PluginLoader> loaders = new EnumMap<>(PluginManifest.PluginType.class);

    /** Plugin IDs that user has enabled; persisted across restarts. */
    private final Set<String> enabledStates = new HashSet<>();
    /** Plugin IDs marked for uninstall on next restart; persisted. */
    private final Set<String> pendingUninstall = new HashSet<>();

    private PluginManager() {
        this.pluginsDirectory = Metadata.HMCL_LOCAL_HOME.resolve("plugins");
        this.pluginDataDirectory = Metadata.HMCL_LOCAL_HOME.resolve("plugin-data");
        this.stateFile = Metadata.HMCL_LOCAL_HOME.resolve("plugin-states.json");

        try {
            Files.createDirectories(pluginsDirectory);
            Files.createDirectories(pluginDataDirectory);
        } catch (IOException e) {
            LOG.error("Failed to create plugin directories", e);
        }

        loadStates();

        // Register plugin loaders
        loaders.put(PluginManifest.PluginType.JAVA, new JavaPluginLoader());
        loaders.put(PluginManifest.PluginType.KOTLIN, new JavaPluginLoader());
        loaders.put(PluginManifest.PluginType.JAVASCRIPT, new JavaScriptPluginLoader());
    }

    public static PluginManager getInstance() {
        if (instance == null) {
            instance = new PluginManager();
        }
        return instance;
    }

    /**
     * Load persisted plugin states (enabled / pending uninstall) from disk.
     */
    private void loadStates() {
        if (!Files.exists(stateFile)) {
            return;
        }
        try {
            String json = Files.readString(stateFile, StandardCharsets.UTF_8);
            PluginStates states = GSON.fromJson(json, PluginStates.class);
            if (states != null) {
                if (states.enabled != null) {
                    enabledStates.addAll(states.enabled);
                }
                if (states.pendingUninstall != null) {
                    pendingUninstall.addAll(states.pendingUninstall);
                }
            }
        } catch (Exception e) {
            LOG.warning("Failed to load plugin states", e);
        }
    }

    /**
     * Persist plugin states to disk.
     */
    private void saveStates() {
        try {
            PluginStates states = new PluginStates();
            states.enabled = new ArrayList<>(enabledStates);
            states.pendingUninstall = new ArrayList<>(pendingUninstall);
            Files.writeString(stateFile, GSON.toJson(states), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warning("Failed to save plugin states", e);
        }
    }

    private static final class PluginStates {
        List<String> enabled;
        List<String> pendingUninstall;
    }

    /**
     * Discover and load all plugins from the plugins directory.
     */
    public void discoverPlugins() {
        LOG.info("Discovering plugins...");

        try {
            if (!Files.exists(pluginsDirectory)) {
                return;
            }

            List<Path> nplFiles = Files.list(pluginsDirectory)
                    .filter(path -> path.toString().endsWith(".npl"))
                    .collect(Collectors.toList());

            for (Path nplFile : nplFiles) {
                try {
                    // Read plugin ID from manifest to check uninstall marker
                    String pluginId = readPluginId(nplFile);

                    if (pluginId != null && pendingUninstall.contains(pluginId)) {
                        LOG.info("Uninstalling plugin marked for removal: " + pluginId);
                        Files.deleteIfExists(nplFile);
                        Path extractDir = pluginDataDirectory.resolve(
                                nplFile.getFileName().toString().replace(".npl", ""));
                        if (Files.exists(extractDir)) {
                            FileUtils.deleteDirectory(extractDir);
                        }
                        pendingUninstall.remove(pluginId);
                        enabledStates.remove(pluginId);
                        saveStates();
                        continue;
                    }

                    PluginContainer container = loadPlugin(nplFile);

                    // Auto-enable if user previously enabled this plugin
                    if (enabledStates.contains(container.getManifest().getId())) {
                        enablePlugin(container.getManifest().getId());
                    }
                } catch (Exception e) {
                    LOG.error("Failed to load plugin: " + nplFile.getFileName(), e);
                }
            }

            LOG.info("Discovered " + plugins.size() + " plugin(s)");
        } catch (IOException e) {
            LOG.error("Failed to discover plugins", e);
        }
    }

    /**
     * Read the plugin ID from an .npl file's manifest without extracting it.
     */
    private String readPluginId(Path nplFile) {
        try (ZipFile zipFile = new ZipFile(nplFile.toFile())) {
            ZipEntry manifestEntry = zipFile.getEntry("plugin.json");
            if (manifestEntry == null) {
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(
                    zipFile.getInputStream(manifestEntry), StandardCharsets.UTF_8)) {
                PluginManifest manifest = PluginManifest.fromJson(reader);
                return manifest.getId();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Load a plugin from an .npl file.
     * This method modifies ObservableList and must be called from JavaFX thread.
     */
    public PluginContainer loadPlugin(Path nplFile) throws IOException {
        LOG.info("Loading plugin: " + nplFile.getFileName());

        // Prepare plugin (extract and load class) - this contains IO operations
        PreparedPlugin prepared = preparePluginInternal(nplFile);

        // Register plugin (add to lists and call lifecycle methods)
        // This part modifies ObservableList and must be on JavaFX thread
        PluginContainer container = new PluginContainer(prepared.plugin, prepared.context, nplFile);
        plugins.add(container);
        pluginMap.put(prepared.manifest.getId(), container);

        // Initialize plugin
        prepared.plugin.onLoad(prepared.context);

        LOG.info("Loaded plugin: " + prepared.manifest.getName() + " v" + prepared.manifest.getVersion());
        return container;
    }

    /**
     * Prepare a plugin by extracting files and loading classes.
     * This method performs IO operations and can be called from any thread.
     * Returns a PreparedPlugin that can be registered on the JavaFX thread.
     */
    private PreparedPlugin preparePluginInternal(Path nplFile) throws IOException {
        LOG.info("Preparing plugin: " + nplFile.getFileName());

        // Extract plugin to temporary directory
        Path extractDir = pluginDataDirectory.resolve(nplFile.getFileName().toString().replace(".npl", ""));
        if (Files.exists(extractDir)) {
            FileUtils.deleteDirectory(extractDir);
        }
        Files.createDirectories(extractDir);

        // Read manifest from .npl
        PluginManifest manifest;
        try (ZipFile zipFile = new ZipFile(nplFile.toFile())) {
            ZipEntry manifestEntry = zipFile.getEntry("plugin.json");
            if (manifestEntry == null) {
                throw new IOException("plugin.json not found in " + nplFile.getFileName());
            }

            try (InputStreamReader reader = new InputStreamReader(
                    zipFile.getInputStream(manifestEntry), StandardCharsets.UTF_8)) {
                manifest = PluginManifest.fromJson(reader);
            }

            // Extract all files
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path targetPath = extractDir.resolve(entry.getName());

                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(zipFile.getInputStream(entry), targetPath);
                }
            }
        }

        // Validate manifest
        if (manifest.getId() == null || manifest.getName() == null ||
            manifest.getVersion() == null || manifest.getType() == null ||
            manifest.getEntrypoint() == null) {
            throw new IOException("Invalid plugin manifest in " + nplFile.getFileName());
        }

        // Check if plugin already loaded
        if (pluginMap.containsKey(manifest.getId())) {
            throw new IOException("Plugin already loaded: " + manifest.getId());
        }

        // Get appropriate loader
        PluginLoader loader = loaders.get(manifest.getType());
        if (loader == null) {
            throw new IOException("No loader found for plugin type: " + manifest.getType());
        }

        // Load the plugin class
        Plugin plugin = loader.load(manifest, extractDir, nplFile);
        PluginContext context = new PluginContext(manifest, extractDir, plugin.getClass().getClassLoader());

        return new PreparedPlugin(plugin, context, manifest, nplFile);
    }

    /**
     * Public method to prepare a plugin in background thread.
     * Call registerPreparedPlugin() on JavaFX thread to complete installation.
     */
    public PreparedPlugin preparePlugin(Path nplFile) throws IOException {
        return preparePluginInternal(nplFile);
    }

    /**
     * Register a prepared plugin. Must be called from JavaFX thread.
     */
    public PluginContainer registerPreparedPlugin(PreparedPlugin prepared) {
        // Create container
        PluginContainer container = new PluginContainer(prepared.plugin, prepared.context, prepared.nplFile);
        plugins.add(container);
        pluginMap.put(prepared.manifest.getId(), container);

        // Initialize plugin
        prepared.plugin.onLoad(prepared.context);

        LOG.info("Registered plugin: " + prepared.manifest.getName() + " v" + prepared.manifest.getVersion());
        return container;
    }

    /**
     * Internal class to hold a prepared plugin before registration.
     */
    public static class PreparedPlugin {
        final Plugin plugin;
        final PluginContext context;
        final PluginManifest manifest;
        final Path nplFile;

        PreparedPlugin(Plugin plugin, PluginContext context, PluginManifest manifest, Path nplFile) {
            this.plugin = plugin;
            this.context = context;
            this.manifest = manifest;
            this.nplFile = nplFile;
        }
    }

    /**
     * Enable a plugin.
     */
    public void enablePlugin(String pluginId) {
        PluginContainer container = pluginMap.get(pluginId);
        if (container != null && !container.isEnabled()) {
            try {
                container.getPlugin().onEnable();
                container.setEnabled(true);
                enabledStates.add(pluginId);
                saveStates();
                LOG.info("Enabled plugin: " + pluginId);
            } catch (Exception e) {
                LOG.error("Failed to enable plugin: " + pluginId, e);
            }
        }
    }

    /**
     * Disable a plugin.
     */
    public void disablePlugin(String pluginId) {
        PluginContainer container = pluginMap.get(pluginId);
        if (container != null && container.isEnabled()) {
            try {
                container.getPlugin().onDisable();
                container.setEnabled(false);
                enabledStates.remove(pluginId);
                saveStates();
                PluginUIRegistry.unregisterAll(pluginId);
                LOG.info("Disabled plugin: " + pluginId);
            } catch (Exception e) {
                LOG.error("Failed to disable plugin: " + pluginId, e);
            }
        }
    }

    /**
     * Unload a plugin.
     */
    public void unloadPlugin(String pluginId) {
        PluginContainer container = pluginMap.get(pluginId);
        if (container != null) {
            if (container.isEnabled()) {
                disablePlugin(pluginId);
            }

            try {
                container.getPlugin().onUnload();
                plugins.remove(container);
                pluginMap.remove(pluginId);
                PluginUIRegistry.unregisterAll(pluginId);
                LOG.info("Unloaded plugin: " + pluginId);
            } catch (Exception e) {
                LOG.error("Failed to unload plugin: " + pluginId, e);
            }
        }
    }

    /**
     * Install a plugin from an .npl file.
     * This method handles file I/O and should be called from a background thread.
     * The actual plugin loading and enabling will be scheduled on the JavaFX thread.
     */
    public PluginContainer installPlugin(Path nplFile) throws IOException {
        Path targetPath = pluginsDirectory.resolve(nplFile.getFileName());
        if (!nplFile.equals(targetPath)) {
            Files.copy(nplFile, targetPath);
        }

        // Load plugin on JavaFX thread since it modifies ObservableList
        PluginContainer container = loadPlugin(targetPath);

        // Enable plugin (will trigger onEnable which may contain UI operations)
        enablePlugin(container.getManifest().getId());

        return container;
    }

    /**
     * Uninstall a plugin.
     */
    public void uninstallPlugin(String pluginId) throws IOException {
        PluginContainer container = pluginMap.get(pluginId);
        if (container != null) {
            unloadPlugin(pluginId);

            // Delete .npl file
            Files.deleteIfExists(container.getNplFile());

            // Delete extracted data
            Path extractDir = container.getContext().getPluginDirectory();
            if (Files.exists(extractDir)) {
                FileUtils.deleteDirectory(extractDir);
            }

            // Clean up states
            enabledStates.remove(pluginId);
            pendingUninstall.remove(pluginId);
            saveStates();

            LOG.info("Uninstalled plugin: " + pluginId);
        }
    }

    /**
     * Mark a plugin for uninstall on next restart.
     * Used for enabled plugins that cannot be safely uninstalled while running.
     */
    public void markForUninstall(String pluginId) {
        pendingUninstall.add(pluginId);
        enabledStates.remove(pluginId);
        saveStates();
        LOG.info("Marked plugin for uninstall on next restart: " + pluginId);
    }

    /**
     * Check if a plugin is marked for uninstall.
     */
    public boolean isMarkedForUninstall(String pluginId) {
        return pendingUninstall.contains(pluginId);
    }

    public ObservableList<PluginContainer> getPlugins() {
        return FXCollections.unmodifiableObservableList(plugins);
    }

    public PluginContainer getPlugin(String pluginId) {
        return pluginMap.get(pluginId);
    }

    public Path getPluginsDirectory() {
        return pluginsDirectory;
    }
}

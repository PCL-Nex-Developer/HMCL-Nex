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

import com.google.gson.JsonObject;
import javafx.stage.Stage;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.ui.Controllers;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;

/// Exposes package metadata, storage locations, class loading, and UI registration to one plugin.
@NotNullByDefault
public final class PluginContext {
    /// Authoritative package manifest.
    private final PluginManifest manifest;

    /// Directory containing extracted package resources.
    private final Path packageDirectory;

    /// Persistent private storage directory for this plugin ID.
    private final Path dataDirectory;

    /// Class loader that defined the plugin lifecycle implementation.
    private final ClassLoader classLoader;

    /// Creates a plugin context.
    ///
    /// @param manifest package manifest
    /// @param packageDirectory extracted package directory
    /// @param dataDirectory persistent plugin data directory
    /// @param classLoader plugin class loader
    public PluginContext(
            PluginManifest manifest,
            Path packageDirectory,
            Path dataDirectory,
            ClassLoader classLoader
    ) {
        this.manifest = manifest;
        this.packageDirectory = packageDirectory;
        this.dataDirectory = dataDirectory;
        this.classLoader = classLoader;
    }

    /// Returns the authoritative package manifest.
    ///
    /// @return plugin manifest
    public PluginManifest getManifest() {
        return manifest;
    }

    /// Returns the extracted package directory containing bundled resources and libraries.
    ///
    /// @return extracted package directory
    public Path getPluginDirectory() {
        return packageDirectory;
    }

    /// Returns the extracted package directory containing bundled resources and libraries.
    ///
    /// @return extracted package directory
    public Path getPackageDirectory() {
        return packageDirectory;
    }

    /// Returns the loader that defined the lifecycle implementation.
    ///
    /// @return plugin class loader
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    /// Returns the current launcher version.
    ///
    /// @return launcher version
    public String getLauncherVersion() {
        return Metadata.VERSION;
    }

    /// Returns the primary JavaFX stage.
    ///
    /// @return primary stage
    public Stage getPrimaryStage() {
        return Controllers.getStage();
    }

    /// Returns HMCL's launcher-wide local data directory.
    ///
    /// @return launcher data directory
    public Path getLauncherDataDirectory() {
        return Metadata.HMCL_LOCAL_HOME;
    }

    /// Returns the persistent private data directory assigned to this plugin ID.
    ///
    /// @return plugin data directory
    public Path getDataDirectory() {
        return dataDirectory;
    }

    /// Registers a JavaFX sidebar action owned by this plugin.
    ///
    /// @param title displayed sidebar title
    /// @param onAction action invoked when the item is selected
    public void registerSidebarItem(String title, Runnable onAction) {
        PluginUIRegistry.registerSidebarItem(manifest.getId(), title, onAction);
    }

    /// Registers a sidebar item backed by a declarative JavaScript plugin page.
    ///
    /// @param title displayed sidebar title
    /// @param page declarative control tree
    /// @param eventHandler JavaScript event bridge
    public void registerJavaScriptSidebarItem(
            String title,
            JsonObject page,
            JavaScriptPluginPage.EventHandler eventHandler
    ) {
        PluginUIRegistry.registerSidebarItem(manifest.getId(), title, () ->
                Controllers.navigate(new JavaScriptPluginPage(title, page, eventHandler))
        );
    }
}

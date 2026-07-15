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

import java.nio.file.Path;

/**
 * Plugin context provides access to launcher APIs and resources.
 */
public class PluginContext {

    private final PluginManifest manifest;
    private final Path pluginDirectory;
    private final ClassLoader classLoader;

    public PluginContext(PluginManifest manifest, Path pluginDirectory, ClassLoader classLoader) {
        this.manifest = manifest;
        this.pluginDirectory = pluginDirectory;
        this.classLoader = classLoader;
    }

    /**
     * Get the plugin manifest.
     */
    public PluginManifest getManifest() {
        return manifest;
    }

    /**
     * Get the plugin directory (extracted .npl contents).
     */
    public Path getPluginDirectory() {
        return pluginDirectory;
    }

    /**
     * Get the plugin's class loader.
     */
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    /**
     * Get the launcher version.
     */
    public String getLauncherVersion() {
        return Metadata.VERSION;
    }

    /**
     * Get the primary stage.
     */
    public Stage getPrimaryStage() {
        return Controllers.getStage();
    }

    /**
     * Get the launcher data directory.
     */
    public Path getLauncherDataDirectory() {
        return Metadata.HMCL_LOCAL_HOME;
    }

    /**
     * Get the HMCL data directory.
     */
    public Path getDataDirectory() {
        return Metadata.HMCL_LOCAL_HOME;
    }

    /**
     * Register a sidebar item in the launcher main page.
     * The item will appear under the "Plugin" dropdown menu.
     *
     * @param title The display title for the sidebar item
     * @param onAction The action to execute when the item is clicked
     */
    public void registerSidebarItem(String title, Runnable onAction) {
        PluginUIRegistry.registerSidebarItem(manifest.getId(), title, onAction);
    }

    /**
     * Register a sidebar item backed by a declarative JavaFX page from a JavaScript plugin.
     */
    public void registerJavaScriptSidebarItem(String title, JsonObject page,
                                              JavaScriptPluginPage.EventHandler eventHandler) {
        PluginUIRegistry.registerSidebarItem(manifest.getId(), title, () -> {
            Controllers.navigate(new JavaScriptPluginPage(title, page, eventHandler));
        });
    }
}

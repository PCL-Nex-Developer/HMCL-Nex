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

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Registry for plugin-contributed sidebar items in the launcher main page.
 * Plugins register items via {@link PluginContext#registerSidebarItem}.
 */
public final class PluginUIRegistry {

    private PluginUIRegistry() {
    }

    /**
     * A sidebar item contributed by a plugin.
     */
    public static final class SidebarItem {
        private final String pluginId;
        private final String title;
        private final Runnable onAction;

        public SidebarItem(String pluginId, String title, Runnable onAction) {
            this.pluginId = pluginId;
            this.title = title;
            this.onAction = onAction;
        }

        public String getPluginId() {
            return pluginId;
        }

        public String getTitle() {
            return title;
        }

        public Runnable getOnAction() {
            return onAction;
        }
    }

    private static final ObservableList<SidebarItem> sidebarItems = FXCollections.observableArrayList();

    /**
     * Get the observable list of plugin sidebar items.
     * The main page sidebar listens to this list and updates accordingly.
     */
    public static ObservableList<SidebarItem> getSidebarItems() {
        return sidebarItems;
    }

    /**
     * Register a sidebar item for a plugin.
     */
    public static void registerSidebarItem(String pluginId, String title, Runnable onAction) {
        SidebarItem item = new SidebarItem(pluginId, title, onAction);
        if (javafx.application.Platform.isFxApplicationThread()) {
            sidebarItems.add(item);
        } else {
            javafx.application.Platform.runLater(() -> sidebarItems.add(item));
        }
        LOG.info("Plugin " + pluginId + " registered sidebar item: " + title);
    }

    /**
     * Remove all sidebar items registered by a plugin.
     * Called automatically when a plugin is disabled or unloaded.
     */
    public static void unregisterAll(String pluginId) {
        Runnable removal = () -> sidebarItems.removeIf(item -> item.getPluginId().equals(pluginId));
        if (javafx.application.Platform.isFxApplicationThread()) {
            removal.run();
        } else {
            javafx.application.Platform.runLater(removal);
        }
    }
}

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

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.nio.file.Path;

/**
 * Container for a loaded plugin instance.
 */
public class PluginContainer {

    private final Plugin plugin;
    private final PluginContext context;
    private final Path nplFile;
    private final BooleanProperty enabled = new SimpleBooleanProperty(false);
    private Object userData;

    public PluginContainer(Plugin plugin, PluginContext context, Path nplFile) {
        this.plugin = plugin;
        this.context = context;
        this.nplFile = nplFile;
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public PluginContext getContext() {
        return context;
    }

    public Path getNplFile() {
        return nplFile;
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    public BooleanProperty enabledProperty() {
        return enabled;
    }

    public PluginManifest getManifest() {
        return context.getManifest();
    }

    public Object getUserData() {
        return userData;
    }

    public void setUserData(Object userData) {
        this.userData = userData;
    }
}

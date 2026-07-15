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
package org.jackhuang.hmcl.plugin.loader;

import org.jackhuang.hmcl.plugin.Plugin;
import org.jackhuang.hmcl.plugin.PluginManifest;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Interface for plugin loaders.
 */
public interface PluginLoader {

    /**
     * Load a plugin from the extracted directory.
     *
     * @param manifest Plugin manifest
     * @param extractedDir Directory where plugin is extracted
     * @param nplFile Original .npl file
     * @return Loaded plugin instance
     * @throws IOException If loading fails
     */
    Plugin load(PluginManifest manifest, Path extractedDir, Path nplFile) throws IOException;
}

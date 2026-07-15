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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Loader for Java and Kotlin plugins.
 */
public class JavaPluginLoader implements PluginLoader {

    @Override
    public Plugin load(PluginManifest manifest, Path extractedDir, Path nplFile) throws IOException {
        // Find all .jar files in the plugin directory
        List<URL> urls = new ArrayList<>();

        // Add root directory (for classes directory)
        urls.add(extractedDir.toUri().toURL());

        // Add all .jar files
        if (Files.exists(extractedDir)) {
            urls.addAll(Files.walk(extractedDir)
                    .filter(path -> path.toString().endsWith(".jar"))
                    .map(path -> {
                        try {
                            return path.toUri().toURL();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toList()));
        }

        // Create class loader with access to HMCL classes
        URLClassLoader classLoader = new URLClassLoader(
                urls.toArray(new URL[0]),
                JavaPluginLoader.class.getClassLoader()
        );

        // Load the plugin class
        try {
            Class<?> pluginClass = classLoader.loadClass(manifest.getEntrypoint());

            if (!Plugin.class.isAssignableFrom(pluginClass)) {
                throw new IOException("Plugin class must implement Plugin interface: " + manifest.getEntrypoint());
            }

            return (Plugin) pluginClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IOException("Failed to instantiate plugin: " + manifest.getEntrypoint(), e);
        }
    }
}

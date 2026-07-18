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
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/// Loads Java and Kotlin lifecycle entry points from extracted plugin packages.
@NotNullByDefault
public final class JavaPluginLoader implements PluginLoader {
    /// Creates a JVM plugin loader.
    public JavaPluginLoader() {
    }

    /// Loads and instantiates the manifest entry point with access to HMCL and startup Mixin classes.
    ///
    /// @param manifest validated plugin manifest
    /// @param extractedDir extracted package directory
    /// @param nplFile installed package path
    /// @return instantiated lifecycle implementation
    /// @throws IOException if class path discovery or instantiation fails
    @Override
    public Plugin load(PluginManifest manifest, Path extractedDir, Path nplFile) throws IOException {
        List<URL> urls = new ArrayList<>();
        urls.add(extractedDir.toUri().toURL());

        if (Files.isDirectory(extractedDir)) {
            try (Stream<Path> files = Files.walk(extractedDir)) {
                for (Path jar : files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .sorted()
                        .toList()) {
                    urls.add(jar.toUri().toURL());
                }
            }
        }

        URLClassLoader classLoader = new URLClassLoader(
                urls.toArray(URL[]::new),
                JavaPluginLoader.class.getClassLoader()
        );
        try {
            Class<?> pluginClass = classLoader.loadClass(manifest.getEntrypoint());
            if (!Plugin.class.isAssignableFrom(pluginClass)) {
                throw new IOException("Plugin class must implement Plugin: " + manifest.getEntrypoint());
            }
            return (Plugin) pluginClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError exception) {
            classLoader.close();
            throw new IOException("Failed to instantiate plugin: " + manifest.getEntrypoint(), exception);
        } catch (IOException exception) {
            classLoader.close();
            throw exception;
        }
    }
}

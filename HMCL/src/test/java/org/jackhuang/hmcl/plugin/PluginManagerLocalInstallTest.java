/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies safe local `.npl` installation and restart-only update staging.
@NotNullByDefault
public final class PluginManagerLocalInstallTest {
    /// Prepares, registers, and enables a previously unknown plugin ID.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package preparation or lifecycle registration fails
    @Test
    public void prepareNewPluginForRuntimeRegistration(@TempDir Path temporaryDirectory) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        Path sourcePackage = temporaryDirectory.resolve("new-plugin.npl");
        writePluginPackage(sourcePackage, "dev.hmclnex.test.local", "1.0.0");

        PluginManager.LocalPluginInstallation installation =
                manager.prepareLocalPluginInstallation(sourcePackage);

        assertFalse(installation.isRestartRequired());
        assertEquals("dev.hmclnex.test.local", installation.getManifest().getId());
        assertNotNull(installation.getPreparedPlugin());

        PluginContainer container = manager.registerPreparedPlugin(installation.getPreparedPlugin());
        manager.enablePlugin(container.getManifest().getId());
        assertTrue(container.isEnabled());
    }

    /// Stages a same-ID replacement without runtime registration and cancels pending uninstall state.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package staging or inspection fails
    @Test
    public void stageExistingPluginForRestart(@TempDir Path temporaryDirectory) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String pluginId = "dev.hmclnex.test.update";
        Path oldPackage = manager.getPluginsDirectory().resolve("legacy-name.npl");
        Path replacementPackage = temporaryDirectory.resolve("legacy-name.npl");
        writePluginPackage(oldPackage, pluginId, "1.0.0");
        writePluginPackage(replacementPackage, pluginId, "2.0.0");
        manager.markForUninstall(pluginId);

        PluginManager.LocalPluginInstallation installation =
                manager.prepareLocalPluginInstallation(replacementPackage);

        assertTrue(installation.isRestartRequired());
        assertFalse(manager.isMarkedForUninstall(pluginId));
        assertTrue(manager.getPlugins().isEmpty());
        assertFalse(Files.exists(oldPackage));

        try (Stream<Path> files = Files.list(manager.getPluginsDirectory())) {
            Path installedPackage = files
                    .filter(path -> path.getFileName().toString().endsWith(".npl"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("2.0.0", readManifest(installedPackage).getVersion());
        }
    }

    /// Keeps an already loaded same-ID plugin registered exactly once while replacing its package for restart.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if initial preparation, registration, or update staging fails
    @Test
    public void stageLoadedPluginWithoutDuplicateRegistration(@TempDir Path temporaryDirectory) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String pluginId = "dev.hmclnex.test.loaded-update";
        Path initialPackage = temporaryDirectory.resolve("initial.npl");
        Path replacementPackage = temporaryDirectory.resolve("replacement.npl");
        writePluginPackage(initialPackage, pluginId, "1.0.0");
        writePluginPackage(replacementPackage, pluginId, "2.0.0");

        PluginManager.LocalPluginInstallation initialInstallation =
                manager.prepareLocalPluginInstallation(initialPackage);
        PluginContainer originalContainer = manager.registerPreparedPlugin(
                initialInstallation.getPreparedPlugin()
        );
        manager.enablePlugin(pluginId);

        PluginManager.LocalPluginInstallation updateInstallation =
                manager.prepareLocalPluginInstallation(replacementPackage);

        assertTrue(updateInstallation.isRestartRequired());
        assertEquals(1, manager.getPlugins().size());
        assertSame(originalContainer, manager.getPlugin(pluginId));
        assertTrue(originalContainer.isRestartRequired());
        assertEquals("1.0.0", originalContainer.getManifest().getVersion());
        assertEquals("2.0.0", readManifest(originalContainer.getNplFile()).getVersion());
    }

    /// Rejects paths that are not readable regular `.npl` files before installation work begins.
    ///
    /// @param temporaryDirectory isolated test directory
    @Test
    public void rejectInvalidLocalPackagePath(@TempDir Path temporaryDirectory) {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));

        assertThrows(
                IOException.class,
                () -> manager.prepareLocalPluginInstallation(temporaryDirectory.resolve("missing.npl"))
        );
        assertThrows(
                IOException.class,
                () -> manager.prepareLocalPluginInstallation(temporaryDirectory.resolve("wrong.zip"))
        );
    }

    /// Writes a minimal valid package whose entry point is supplied by the test class path.
    ///
    /// @param target target package path
    /// @param pluginId package plugin ID
    /// @param version package version
    /// @throws IOException if package creation fails
    private static void writePluginPackage(Path target, String pluginId, String version) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        String manifest = """
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "name": "Local Install Test",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "%s"
                }
                """.formatted(pluginId, version, TestPlugin.class.getName());

        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            ZipEntry entry = new ZipEntry("plugin.json");
            entry.setTime(0);
            output.putNextEntry(entry);
            output.write(manifest.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    /// Reads the validated manifest from a test package.
    ///
    /// @param packageFile test package
    /// @return validated manifest
    /// @throws IOException if the package or manifest is invalid
    private static PluginManifest readManifest(Path packageFile) throws IOException {
        try (ZipFile zipFile = new ZipFile(packageFile.toFile())) {
            ZipEntry entry = Objects.requireNonNull(zipFile.getEntry("plugin.json"));
            try (InputStreamReader reader = new InputStreamReader(
                    zipFile.getInputStream(entry),
                    StandardCharsets.UTF_8
            )) {
                return PluginManifest.fromJson(reader);
            }
        }
    }

    /// Minimal lifecycle implementation used to verify preparation and registration.
    @NotNullByDefault
    public static final class TestPlugin implements Plugin {
        /// Manifest received during `onLoad`.
        private PluginManifest manifest;

        /// Creates the test plugin.
        public TestPlugin() {
        }

        /// Stores the package context supplied by the manager.
        ///
        /// @param context test plugin context
        @Override
        public void onLoad(PluginContext context) {
            manifest = context.getManifest();
        }

        /// Activates the no-op test plugin.
        @Override
        public void onEnable() {
        }

        /// Deactivates the no-op test plugin.
        @Override
        public void onDisable() {
        }

        /// Returns the manifest received during load.
        ///
        /// @return test plugin manifest
        @Override
        public PluginManifest getManifest() {
            return Objects.requireNonNull(manifest);
        }
    }
}

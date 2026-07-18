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
package org.jackhuang.hmcl.plugin.store;

import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/// Verifies remote repository identity, checksums, API metadata, and latest-version selection.
@NotNullByDefault
public final class PluginStoreManifestTest {
    /// Selects the greatest semantic version rather than trusting remote JSON order.
    @Test
    public void selectLatestVersion() throws IOException {
        PluginStoreManifest manifest = JsonUtils.GSON.fromJson("""
                {
                  "schemaVersion": 1,
                  "id": "dev.hmclnex.test.store",
                  "versions": [
                    {
                      "version": "1.9.0",
                      "packageUrl": "https://example.com/plugin-1.9.0.npl",
                      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                      "pluginApiVersion": 2,
                      "size": 1024
                    },
                    {
                      "version": "1.10.0",
                      "packageUrl": "https://example.com/plugin-1.10.0.npl",
                      "sha256": "1111111111111111111111111111111111111111111111111111111111111111",
                      "pluginApiVersion": 2,
                      "size": 2048
                    }
                  ]
                }
                """, PluginStoreManifest.class);
        assertNotNull(manifest);
        manifest.validate("dev.hmclnex.test.store");
        assertNotNull(manifest.getLatestVersion());
        assertEquals("1.10.0", manifest.getLatestVersion().getVersion());
    }
}

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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies plugin manifest schema v2 and Mixin-specific validation.
@NotNullByDefault
public final class PluginManifestTest {
    /// Parses a valid JVM Mixin manifest and exposes immutable configuration names.
    @Test
    public void parseMixinManifest() throws IOException {
        PluginManifest manifest = PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 2,
                  "id": "dev.hmclnex.test.mixin",
                  "name": "Mixin Test",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclnex.test.Plugin",
                  "dependencies": ["dev.hmclnex.test.base"],
                  "mixins": ["mixins.dev.hmclnex.test.json"]
                }
                """));

        assertEquals(PluginManifest.CURRENT_SCHEMA_VERSION, manifest.getSchemaVersion());
        assertTrue(manifest.hasMixins());
        assertEquals("mixins.dev.hmclnex.test.json", manifest.getMixins().get(0));
    }

    /// Rejects Mixin declarations on JavaScript plugins because they run outside the JVM.
    @Test
    public void rejectJavaScriptMixins() {
        assertThrows(IOException.class, () -> PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 2,
                  "id": "dev.hmclnex.test.javascript",
                  "name": "JavaScript Test",
                  "version": "1.0.0",
                  "type": "javascript",
                  "entrypoint": "main.js",
                  "mixins": ["mixins.invalid.json"]
                }
                """)));
    }
}

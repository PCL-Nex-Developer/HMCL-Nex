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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies version ordering used by plugin compatibility and update checks.
@NotNullByDefault
public final class PluginVersionTest {
    /// Compares numeric components without lexical ordering mistakes.
    @Test
    public void compareNumericVersions() {
        assertTrue(PluginVersion.compare("1.10.0", "1.9.9") > 0);
        assertEquals(0, PluginVersion.compare("v2.0", "2.0.0"));
    }

    /// Sorts prereleases before their corresponding final release.
    @Test
    public void comparePrereleaseVersions() {
        assertTrue(PluginVersion.compare("3.0.0-rc.1", "3.0.0") < 0);
        assertTrue(PluginVersion.compare("3.0.0-beta.2", "3.0.0-rc.1") < 0);
    }
}

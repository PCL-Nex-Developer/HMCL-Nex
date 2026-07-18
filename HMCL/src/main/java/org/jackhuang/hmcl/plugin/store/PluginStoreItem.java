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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Combines one registry entry with its resolved repository manifest.
@NotNullByDefault
public final class PluginStoreItem {
    /// Registry metadata used for listing and search.
    private final PluginStoreRegistry.PluginStoreEntry entry;

    /// Resolved repository manifest, or `null` when that repository is temporarily unavailable.
    private final @Nullable PluginStoreManifest manifest;

    /// Creates a resolved or partially resolved store item.
    ///
    /// @param entry registry entry
    /// @param manifest repository manifest or `null`
    public PluginStoreItem(
            PluginStoreRegistry.PluginStoreEntry entry,
            @Nullable PluginStoreManifest manifest
    ) {
        this.entry = entry;
        this.manifest = manifest;
    }

    /// Returns registry metadata.
    ///
    /// @return registry entry
    public PluginStoreRegistry.PluginStoreEntry getEntry() {
        return entry;
    }

    /// Returns the repository manifest when it resolved successfully.
    ///
    /// @return repository manifest or `null`
    public @Nullable PluginStoreManifest getManifest() {
        return manifest;
    }

    /// Returns the greatest published version from the repository manifest.
    ///
    /// @return latest version or `null`
    public @Nullable PluginStoreManifest.PluginVersionEntry getLatestVersion() {
        return manifest == null ? null : manifest.getLatestVersion();
    }
}

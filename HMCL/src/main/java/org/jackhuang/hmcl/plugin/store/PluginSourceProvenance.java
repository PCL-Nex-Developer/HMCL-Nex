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
package org.jackhuang.hmcl.plugin.store;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Immutable security provenance for one selected plugin source.
@NotNullByDefault
public final class PluginSourceProvenance {
    /// Whether the selected source is the built-in official registry.
    private final boolean official;

    /// Credential-safe configured source host captured during dependency resolution.
    private final String hostIdentity;

    /// Captures security provenance directly from an immutable source configuration.
    ///
    /// Mutable aliases and remote registry names intentionally do not participate in this value.
    ///
    /// @param source selected source configuration
    /// @return immutable official status and safe host identity
    public static PluginSourceProvenance from(PluginSource source) {
        Objects.requireNonNull(source, "source");
        return new PluginSourceProvenance(
                source.isOfficial(),
                PluginSourceLabels.sourceUrlFallback(source)
        );
    }

    /// Creates one immutable source provenance snapshot.
    ///
    /// @param official official status derived from [PluginSource#isOfficial()]
    /// @param hostIdentity credential-safe configured source host
    private PluginSourceProvenance(boolean official, String hostIdentity) {
        this.official = official;
        this.hostIdentity = Objects.requireNonNull(hostIdentity, "hostIdentity");
    }

    /// Returns whether the source is the built-in official registry.
    ///
    /// @return official source status
    public boolean isOfficial() {
        return official;
    }

    /// Returns the credential-safe configured source host.
    ///
    /// @return host identity without URL credentials, path, query, or fragment
    public String getHostIdentity() {
        return hostIdentity;
    }
}

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
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Immutable aggregate catalog produced by one plugin-source refresh generation.
@NotNullByDefault
public final class PluginStoreSnapshot {
    /// Refresh generation associated with this independently usable aggregate result.
    private final long generation;

    /// Source outcomes in the supplied source priority order.
    private final @Unmodifiable List<PluginSourceLoadResult> sourceResults;

    /// First successful item for every plugin ID, retaining source priority order.
    private final @Unmodifiable Map<String, PluginStoreItem> winningItems;

    /// Lower-priority successful items grouped below their selected winner by plugin ID.
    private final @Unmodifiable Map<String, @Unmodifiable List<PluginStoreItem>> conflictCandidates;

    /// Registry-level source failures retained for source detail views and banners.
    private final @Unmodifiable List<PluginSourceLoadResult> failures;

    /// Creates an aggregate snapshot and derives deterministic winners from ordered source results.
    ///
    /// @param generation refresh generation that computed this snapshot
    /// @param sourceResults source outcomes ordered by configured priority
    public PluginStoreSnapshot(long generation, @Unmodifiable List<PluginSourceLoadResult> sourceResults) {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        this.generation = generation;
        this.sourceResults = List.copyOf(sourceResults);

        Map<String, PluginStoreItem> winners = new LinkedHashMap<>();
        Map<String, List<PluginStoreItem>> conflicts = new LinkedHashMap<>();
        List<PluginSourceLoadResult> sourceFailures = new ArrayList<>();
        for (PluginSourceLoadResult result : this.sourceResults) {
            if (result.getStatus() == PluginSourceLoadResult.Status.FAILED) {
                sourceFailures.add(result);
            }
            if (!result.isSuccessful()) {
                continue;
            }
            for (PluginStoreItem item : result.getItems()) {
                PluginStoreItem previous = winners.putIfAbsent(item.getEntry().getId(), item);
                if (previous != null) {
                    conflicts.computeIfAbsent(item.getEntry().getId(), ignored -> new ArrayList<>()).add(item);
                }
            }
        }

        winningItems = Collections.unmodifiableMap(winners);
        conflictCandidates = copyConflicts(conflicts);
        failures = List.copyOf(sourceFailures);
    }

    /// Copies conflict candidates and their nested lists before the snapshot exposes them.
    ///
    /// @param conflicts mutable conflict candidates collected while deriving winners
    /// @return immutable priority-ordered conflict candidates
    private static @Unmodifiable Map<String, @Unmodifiable List<PluginStoreItem>> copyConflicts(
            Map<String, List<PluginStoreItem>> conflicts
    ) {
        Map<String, @Unmodifiable List<PluginStoreItem>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<PluginStoreItem>> entry : conflicts.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /// Returns the refresh generation that produced this snapshot.
    ///
    /// @return aggregate refresh generation
    public long getGeneration() {
        return generation;
    }

    /// Returns all configured-source outcomes in priority order.
    ///
    /// @return immutable source outcomes
    public @Unmodifiable List<PluginSourceLoadResult> getSourceResults() {
        return sourceResults;
    }

    /// Returns the first successful item for every plugin ID in source priority order.
    ///
    /// @return immutable winning items by plugin ID
    public @Unmodifiable Map<String, PluginStoreItem> getWinningItems() {
        return winningItems;
    }

    /// Returns successful lower-priority items that conflict with each winner.
    ///
    /// @return immutable conflict candidates by plugin ID
    public @Unmodifiable Map<String, @Unmodifiable List<PluginStoreItem>> getConflictCandidates() {
        return conflictCandidates;
    }

    /// Returns source results whose registries could not be loaded.
    ///
    /// @return immutable failed source results
    public @Unmodifiable List<PluginSourceLoadResult> getFailures() {
        return failures;
    }
}

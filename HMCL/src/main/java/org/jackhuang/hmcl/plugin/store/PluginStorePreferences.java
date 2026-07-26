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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Persists user-owned plugin-store favorites and source selection independently of remote registries.
@NotNullByDefault
final class PluginStorePreferences {
    /// JSON codec used for the small preference document.
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /// Pattern accepted for favorite plugin IDs loaded from disk.
    private static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{1,127}");

    /// Preference file below the launcher-local home.
    private final Path stateFile;

    /// Favorite plugin IDs in stable insertion order.
    private final Set<String> favoritePluginIds = new LinkedHashSet<>();

    /// User-added registry URLs in stable insertion order.
    private final List<String> customRegistryUrls = new ArrayList<>();

    /// Last selected registry URL.
    private String activeRegistryUrl = PluginStoreManager.DEFAULT_REGISTRY_URL;

    /// Loads preferences from the supplied launcher-local home.
    ///
    /// @param localHome launcher-local home
    PluginStorePreferences(Path localHome) {
        stateFile = localHome.resolve("plugin-store.json");
        load();
    }

    /// Loads a valid subset of persisted values and ignores malformed entries.
    private synchronized void load() {
        if (!Files.isRegularFile(stateFile)) {
            return;
        }
        try {
            @Nullable State state = GSON.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), State.class);
            if (state == null) {
                return;
            }
            if (state.favoritePluginIds != null) {
                for (@Nullable String pluginId : state.favoritePluginIds) {
                    if (pluginId != null && ID_PATTERN.matcher(pluginId).matches()) {
                        favoritePluginIds.add(pluginId);
                    }
                }
            }
            if (state.customRegistryUrls != null) {
                for (@Nullable String registryUrl : state.customRegistryUrls) {
                    if (registryUrl != null && !registryUrl.isBlank() && !customRegistryUrls.contains(registryUrl)) {
                        customRegistryUrls.add(registryUrl);
                    }
                }
            }
            if (state.activeRegistryUrl != null && !state.activeRegistryUrl.isBlank()) {
                activeRegistryUrl = state.activeRegistryUrl;
            }
        } catch (IOException | RuntimeException exception) {
            LOG.warning("Failed to load plugin store preferences", exception);
        }
    }

    /// Writes preferences through an atomic replacement when supported by the file system.
    private synchronized void save() {
        State state = new State();
        state.favoritePluginIds = favoritePluginIds.stream().sorted().toList();
        state.customRegistryUrls = List.copyOf(customRegistryUrls);
        state.activeRegistryUrl = activeRegistryUrl;

        Path temporaryFile = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(stateFile.getParent());
            Files.writeString(temporaryFile, GSON.toJson(state), StandardCharsets.UTF_8);
            try {
                Files.move(temporaryFile, stateFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            LOG.warning("Failed to save plugin store preferences", exception);
        } finally {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException exception) {
                LOG.warning("Failed to delete temporary plugin store preference file", exception);
            }
        }
    }

    /// Returns whether the supplied plugin is a user favorite.
    ///
    /// @param pluginId plugin ID
    /// @return favorite state
    synchronized boolean isFavorite(String pluginId) {
        return favoritePluginIds.contains(pluginId);
    }

    /// Updates one favorite and persists the resulting set.
    ///
    /// @param pluginId plugin ID
    /// @param favorite desired favorite state
    /// @throws IllegalArgumentException if the plugin ID cannot be persisted safely
    synchronized void setFavorite(String pluginId, boolean favorite) {
        if (!ID_PATTERN.matcher(pluginId).matches()) {
            throw new IllegalArgumentException("Invalid favorite plugin ID: " + pluginId);
        }
        boolean changed = favorite ? favoritePluginIds.add(pluginId) : favoritePluginIds.remove(pluginId);
        if (changed) {
            save();
        }
    }

    /// Returns an immutable snapshot of favorite plugin IDs.
    ///
    /// @return favorite plugin IDs
    synchronized @Unmodifiable Set<String> getFavoritePluginIds() {
        return Set.copyOf(favoritePluginIds);
    }

    /// Adds one validated custom registry URL and persists it.
    ///
    /// @param registryUrl validated registry URL
    synchronized void addCustomRegistryUrl(String registryUrl) {
        if (!customRegistryUrls.contains(registryUrl)) {
            customRegistryUrls.add(registryUrl);
            save();
        }
    }

    /// Returns custom registry URLs in display order.
    ///
    /// @return custom registry URLs
    synchronized @Unmodifiable List<String> getCustomRegistryUrls() {
        return List.copyOf(customRegistryUrls);
    }

    /// Returns the last selected registry URL.
    ///
    /// @return active registry URL
    synchronized String getActiveRegistryUrl() {
        return activeRegistryUrl;
    }

    /// Persists the active registry URL.
    ///
    /// @param registryUrl validated registry URL
    synchronized void setActiveRegistryUrl(String registryUrl) {
        if (!activeRegistryUrl.equals(registryUrl)) {
            activeRegistryUrl = registryUrl;
            save();
        }
    }

    /// Gson storage model for plugin-store preferences.
    @NotNullByDefault
    private static final class State {
        /// Favorite IDs, or `null` in malformed documents.
        private @Nullable List<@Nullable String> favoritePluginIds;

        /// Custom registry URLs, or `null` in malformed documents.
        private @Nullable List<@Nullable String> customRegistryUrls;

        /// Active registry URL, or `null` in malformed documents.
        private @Nullable String activeRegistryUrl;

        /// Creates an empty preference state for Gson.
        private State() {
        }
    }
}

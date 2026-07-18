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

import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.Reader;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/// Describes one HMCL plugin package through its root `plugin.json` file.
@NotNullByDefault
public final class PluginManifest {
    /// Current manifest schema understood by HMCL and the plugin SDK.
    public static final int CURRENT_SCHEMA_VERSION = 2;

    /// Pattern accepted for plugin IDs and dependency IDs.
    private static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{1,127}");

    /// Manifest schema version; legacy packages without this field use version 1.
    @SerializedName("schemaVersion")
    private int schemaVersion = 1;

    /// Globally unique plugin identifier populated by Gson.
    @SerializedName("id")
    private @Nullable String id;

    /// Human-readable plugin name populated by Gson.
    @SerializedName("name")
    private @Nullable String name;

    /// Plugin release version populated by Gson.
    @SerializedName("version")
    private @Nullable String version;

    /// Optional plugin description populated by Gson.
    @SerializedName("description")
    private @Nullable String description = "";

    /// Optional plugin author populated by Gson.
    @SerializedName("author")
    private @Nullable String author = "";

    /// Runtime implementation type populated by Gson.
    @SerializedName("type")
    private @Nullable PluginType type;

    /// Lifecycle entry point class or JavaScript file populated by Gson.
    @SerializedName("entrypoint")
    private @Nullable String entrypoint;

    /// Required plugin IDs that must be loaded before this plugin.
    @SerializedName("dependencies")
    private @Nullable List<@Nullable String> dependencies = List.of();

    /// Minimum compatible HMCL version, or an empty string when unrestricted.
    @SerializedName("minLauncherVersion")
    private @Nullable String minLauncherVersion = "";

    /// Mixin configuration resources contributed by Java or Kotlin plugins.
    @SerializedName("mixins")
    private @Nullable List<@Nullable String> mixins = List.of();

    /// Creates an empty manifest for Gson deserialization.
    public PluginManifest() {
    }

    /// Creates the required portion of a plugin manifest programmatically.
    ///
    /// @param id globally unique plugin ID
    /// @param name human-readable plugin name
    /// @param version plugin version
    /// @param type plugin implementation type
    /// @param entrypoint lifecycle entry point
    public PluginManifest(String id, String name, String version, PluginType type, String entrypoint) {
        this.schemaVersion = CURRENT_SCHEMA_VERSION;
        this.id = id;
        this.name = name;
        this.version = version;
        this.type = type;
        this.entrypoint = entrypoint;
    }

    /// Returns the manifest schema version.
    ///
    /// @return schema version
    public int getSchemaVersion() {
        return schemaVersion;
    }

    /// Returns the validated plugin ID.
    ///
    /// @return plugin ID
    public String getId() {
        return Objects.requireNonNull(id, "Plugin manifest has no id");
    }

    /// Returns the validated plugin display name.
    ///
    /// @return plugin name
    public String getName() {
        return Objects.requireNonNull(name, "Plugin manifest has no name");
    }

    /// Returns the validated plugin version.
    ///
    /// @return plugin version
    public String getVersion() {
        return Objects.requireNonNull(version, "Plugin manifest has no version");
    }

    /// Returns the optional description as a non-null string.
    ///
    /// @return plugin description
    public String getDescription() {
        return Objects.requireNonNullElse(description, "");
    }

    /// Returns the optional author as a non-null string.
    ///
    /// @return plugin author
    public String getAuthor() {
        return Objects.requireNonNullElse(author, "");
    }

    /// Returns the validated plugin implementation type.
    ///
    /// @return plugin type
    public PluginType getType() {
        return Objects.requireNonNull(type, "Plugin manifest has no type");
    }

    /// Returns the validated lifecycle entry point.
    ///
    /// @return entry point class or script path
    public String getEntrypoint() {
        return Objects.requireNonNull(entrypoint, "Plugin manifest has no entrypoint");
    }

    /// Returns an immutable snapshot of required plugin IDs.
    ///
    /// @return dependency IDs
    public @Unmodifiable List<String> getDependencies() {
        @Nullable List<@Nullable String> values = dependencies;
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(Objects::requireNonNull).toList();
    }

    /// Returns the minimum compatible launcher version, or an empty string.
    ///
    /// @return minimum launcher version
    public String getMinLauncherVersion() {
        return Objects.requireNonNullElse(minLauncherVersion, "");
    }

    /// Returns an immutable snapshot of declared Mixin configuration resources.
    ///
    /// @return Mixin configuration resource names
    public @Unmodifiable List<String> getMixins() {
        @Nullable List<@Nullable String> values = mixins;
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(Objects::requireNonNull).toList();
    }

    /// Returns whether this plugin contributes startup-time Mixin transformations.
    ///
    /// @return whether at least one Mixin configuration is declared
    public boolean hasMixins() {
        return mixins != null && !mixins.isEmpty();
    }

    /// Validates all fields used by discovery, dependency resolution, lifecycle loading, and Mixin bootstrap.
    ///
    /// @throws IOException if the manifest is invalid or unsupported
    public void validate() throws IOException {
        if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IOException("Unsupported plugin manifest schemaVersion: " + schemaVersion);
        }
        if (!isValidId(id)) {
            throw new IOException("Invalid plugin id: " + id);
        }
        requireNonBlank(name, "name");
        requireNonBlank(version, "version");
        if (type == null) {
            throw new IOException("Missing plugin type");
        }
        requireNonBlank(entrypoint, "entrypoint");

        Set<String> dependencyIds = new HashSet<>();
        if (dependencies != null) {
            for (@Nullable String dependency : dependencies) {
                if (!isValidId(dependency)) {
                    throw new IOException("Invalid plugin dependency: " + dependency);
                }
                if (getId().equals(dependency)) {
                    throw new IOException("Plugin cannot depend on itself: " + dependency);
                }
                if (!dependencyIds.add(dependency)) {
                    throw new IOException("Duplicate plugin dependency: " + dependency);
                }
            }
        }

        if (mixins != null && !mixins.isEmpty()) {
            if (type == PluginType.JAVASCRIPT) {
                throw new IOException("JavaScript plugins cannot declare Mixin configurations");
            }
            Set<String> configNames = new HashSet<>();
            for (@Nullable String candidate : mixins) {
                if (candidate == null) {
                    throw new IOException("Mixin configuration name cannot be null");
                }
                String config = candidate.trim();
                if (config.isEmpty()
                        || config.startsWith("/")
                        || config.contains("\\")
                        || config.contains(":")
                        || config.contains("../")
                        || !config.endsWith(".json")) {
                    throw new IOException("Invalid Mixin configuration resource: " + candidate);
                }
                if (!configNames.add(config.toLowerCase(Locale.ROOT))) {
                    throw new IOException("Duplicate Mixin configuration resource: " + candidate);
                }
            }
        }
    }

    /// Reads and validates a plugin manifest from JSON.
    ///
    /// @param reader UTF-8 JSON reader
    /// @return validated manifest
    /// @throws IOException if parsing or validation fails
    /// @throws JsonParseException if Gson rejects the JSON representation
    public static PluginManifest fromJson(Reader reader) throws IOException, JsonParseException {
        @Nullable PluginManifest manifest = JsonUtils.GSON.fromJson(reader, PluginManifest.class);
        if (manifest == null) {
            throw new IOException("Plugin manifest is empty");
        }
        manifest.validate();
        return manifest;
    }

    /// Returns whether a nullable string is a structurally valid plugin ID.
    ///
    /// @param value candidate ID
    /// @return whether the ID is valid
    private static boolean isValidId(@Nullable String value) {
        return value != null && ID_PATTERN.matcher(value).matches();
    }

    /// Requires a JSON string field to contain non-whitespace text.
    ///
    /// @param value field value
    /// @param fieldName field name used in diagnostics
    /// @throws IOException if the field is missing or blank
    private static void requireNonBlank(@Nullable String value, String fieldName) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("Missing or blank plugin " + fieldName);
        }
    }

    /// Supported plugin runtime implementations.
    @NotNullByDefault
    public enum PluginType {
        /// Java bytecode plugin loaded from one or more JAR files.
        @SerializedName("java")
        JAVA,

        /// Kotlin bytecode plugin loaded through the Java plugin loader.
        @SerializedName("kotlin")
        KOTLIN,

        /// JavaScript plugin executed by the managed Node.js runtime.
        @SerializedName("javascript")
        JAVASCRIPT
    }
}

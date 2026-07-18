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

import com.google.gson.annotations.SerializedName;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginVersion;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/// Describes all downloadable versions published by one plugin repository.
@NotNullByDefault
public final class PluginStoreManifest {
    /// Current plugin repository manifest schema version.
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /// Required SHA-256 representation for downloadable packages.
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");

    /// Manifest schema version.
    @SerializedName("schemaVersion")
    private int schemaVersion = 1;

    /// Plugin ID bound to this repository manifest.
    @SerializedName("id")
    private @Nullable String id;

    /// Published versions.
    @SerializedName("versions")
    private @Nullable List<@Nullable PluginVersionEntry> versions;

    /// Optional SPDX license expression.
    @SerializedName("license")
    private @Nullable String license;

    /// Optional project website URL.
    @SerializedName("website")
    private @Nullable String website;

    /// Optional source repository URL.
    @SerializedName("source")
    private @Nullable String source;

    /// Creates an empty repository manifest for Gson deserialization.
    public PluginStoreManifest() {
    }

    /// Returns the schema version.
    ///
    /// @return schema version
    public int getSchemaVersion() {
        return schemaVersion;
    }

    /// Returns the validated plugin ID.
    ///
    /// @return plugin ID
    public String getId() {
        return Objects.requireNonNull(id, "Plugin store manifest has no id");
    }

    /// Returns immutable published versions.
    ///
    /// @return version list
    public @Unmodifiable List<PluginVersionEntry> getVersions() {
        @Nullable List<@Nullable PluginVersionEntry> values = versions;
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(Objects::requireNonNull).toList();
    }

    /// Returns the greatest published version independent of JSON array ordering.
    ///
    /// @return latest version or `null`
    public @Nullable PluginVersionEntry getLatestVersion() {
        return getVersions().stream()
                .max(Comparator.comparing(PluginVersionEntry::getVersion, PluginVersion::compare))
                .orElse(null);
    }

    /// Returns the optional license expression.
    ///
    /// @return license expression
    public String getLicense() {
        return Objects.requireNonNullElse(license, "");
    }

    /// Returns the optional project website.
    ///
    /// @return website URL
    public String getWebsite() {
        return Objects.requireNonNullElse(website, "");
    }

    /// Returns the optional source repository.
    ///
    /// @return source URL
    public String getSource() {
        return Objects.requireNonNullElse(source, "");
    }

    /// Validates schema, plugin identity, version uniqueness, checksums, and API declarations.
    ///
    /// @param expectedPluginId plugin ID from the parent registry entry
    /// @throws IOException if the manifest is invalid or belongs to another plugin
    public void validate(String expectedPluginId) throws IOException {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IOException("Unsupported plugin repository schemaVersion: " + schemaVersion);
        }
        if (!expectedPluginId.equals(id)) {
            throw new IOException("Plugin repository manifest ID " + id
                    + " does not match registry entry " + expectedPluginId);
        }
        if (versions == null || versions.isEmpty()) {
            throw new IOException("Plugin repository has no versions: " + expectedPluginId);
        }

        Set<String> publishedVersions = new HashSet<>();
        for (@Nullable PluginVersionEntry version : versions) {
            if (version == null) {
                throw new IOException("Plugin repository contains a null version: " + expectedPluginId);
            }
            version.validate();
            if (!publishedVersions.add(version.getVersion())) {
                throw new IOException("Duplicate plugin version " + version.getVersion() + " for " + expectedPluginId);
            }
        }
    }

    /// Metadata for one downloadable plugin package.
    @NotNullByDefault
    public static final class PluginVersionEntry {
        /// Published package version.
        @SerializedName("version")
        private @Nullable String version;

        /// Download URL for the `.npl` package.
        @SerializedName("packageUrl")
        private @Nullable String packageUrl;

        /// Required SHA-256 checksum.
        @SerializedName("sha256")
        private @Nullable String sha256;

        /// Minimum compatible launcher version.
        @SerializedName("minLauncherVersion")
        private @Nullable String minLauncherVersion;

        /// Optional release notes.
        @SerializedName("releaseNotes")
        private @Nullable String releaseNotes;

        /// Optional ISO release date.
        @SerializedName("releaseDate")
        private @Nullable String releaseDate;

        /// Minimum Java feature version represented as text for registry compatibility.
        @SerializedName("requiredJavaVersion")
        private @Nullable String requiredJavaVersion;

        /// Expected package size in bytes.
        @SerializedName("size")
        private @Nullable Long size;

        /// Required HMCL plugin manifest/API schema version.
        @SerializedName("pluginApiVersion")
        private int pluginApiVersion = 1;

        /// Whether installation or update is expected to require a launcher restart.
        @SerializedName("requiresRestart")
        private boolean requiresRestart;

        /// Release channel such as `stable`, `beta`, or `nightly`.
        @SerializedName("channel")
        private @Nullable String channel = "stable";

        /// Creates an empty version entry for Gson deserialization.
        public PluginVersionEntry() {
        }

        /// Returns the published version.
        ///
        /// @return version string
        public String getVersion() {
            return Objects.requireNonNull(version, "Plugin version has no version string");
        }

        /// Returns the package download URL.
        ///
        /// @return package URL
        public String getPackageUrl() {
            return Objects.requireNonNull(packageUrl, "Plugin version has no packageUrl");
        }

        /// Returns the required SHA-256 checksum.
        ///
        /// @return lower- or upper-case hexadecimal checksum
        public String getSha256() {
            return Objects.requireNonNull(sha256, "Plugin version has no sha256");
        }

        /// Returns the minimum launcher version or an empty string.
        ///
        /// @return minimum launcher version
        public String getMinLauncherVersion() {
            return Objects.requireNonNullElse(minLauncherVersion, "");
        }

        /// Returns optional release notes.
        ///
        /// @return release notes
        public String getReleaseNotes() {
            return Objects.requireNonNullElse(releaseNotes, "");
        }

        /// Returns the optional release date.
        ///
        /// @return release date
        public String getReleaseDate() {
            return Objects.requireNonNullElse(releaseDate, "");
        }

        /// Returns the minimum Java feature version or an empty string.
        ///
        /// @return required Java version
        public String getRequiredJavaVersion() {
            return Objects.requireNonNullElse(requiredJavaVersion, "");
        }

        /// Returns the expected package size.
        ///
        /// @return package size or `null`
        public @Nullable Long getSize() {
            return size;
        }

        /// Returns the required plugin API schema version.
        ///
        /// @return plugin API version
        public int getPluginApiVersion() {
            return pluginApiVersion;
        }

        /// Returns whether this version is expected to require a restart.
        ///
        /// @return restart requirement
        public boolean isRequiresRestart() {
            return requiresRestart;
        }

        /// Returns the normalized release channel.
        ///
        /// @return channel
        public String getChannel() {
            return Objects.requireNonNullElse(channel, "stable");
        }

        /// Validates required download metadata and supported plugin API version.
        ///
        /// @throws IOException if the version entry is invalid
        private void validate() throws IOException {
            if (version == null || version.isBlank()) {
                throw new IOException("Plugin version entry has no version");
            }
            if (packageUrl == null || packageUrl.isBlank()) {
                throw new IOException("Plugin version " + version + " has no packageUrl");
            }
            if (sha256 == null || !SHA256_PATTERN.matcher(sha256).matches()) {
                throw new IOException("Plugin version " + version + " has an invalid SHA-256 checksum");
            }
            if (size == null || size <= 0) {
                throw new IOException("Plugin version " + version + " has an invalid size");
            }
            if (pluginApiVersion < 1 || pluginApiVersion > PluginManifest.CURRENT_SCHEMA_VERSION) {
                throw new IOException("Plugin version " + version + " requires unsupported plugin API "
                        + pluginApiVersion);
            }
        }
    }
}

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

import java.util.List;

/**
 * Plugin manifest with version history.
 */
public class PluginStoreManifest {

    @SerializedName("versions")
    private List<PluginVersion> versions;

    @SerializedName("license")
    private String license;

    @SerializedName("website")
    private String website;

    @SerializedName("source")
    private String source;

    public List<PluginVersion> getVersions() {
        return versions;
    }

    public PluginVersion getLatestVersion() {
        return versions != null && !versions.isEmpty() ? versions.get(0) : null;
    }

    public String getLicense() {
        return license;
    }

    public String getWebsite() {
        return website;
    }

    public String getSource() {
        return source;
    }

    public static class PluginVersion {

        @SerializedName("version")
        private String version;

        @SerializedName("packageUrl")
        private String packageUrl;

        @SerializedName("sha256")
        private String sha256;

        @SerializedName("minLauncherVersion")
        private String minLauncherVersion;

        @SerializedName("releaseNotes")
        private String releaseNotes;

        @SerializedName("releaseDate")
        private String releaseDate;

        @SerializedName("requiredJavaVersion")
        private String requiredJavaVersion;

        @SerializedName("size")
        private Long size;

        public String getVersion() {
            return version;
        }

        public String getPackageUrl() {
            return packageUrl;
        }

        public String getSha256() {
            return sha256;
        }

        public String getMinLauncherVersion() {
            return minLauncherVersion;
        }

        public String getReleaseNotes() {
            return releaseNotes;
        }

        public String getReleaseDate() {
            return releaseDate;
        }

        public String getRequiredJavaVersion() {
            return requiredJavaVersion;
        }

        public Long getSize() {
            return size;
        }
    }
}

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
 * Plugin store registry structure.
 */
public class PluginStoreRegistry {

    @SerializedName("name")
    private String name;

    @SerializedName("description")
    private String description;

    @SerializedName("homepageUrl")
    private String homepageUrl;

    @SerializedName("plugins")
    private List<PluginStoreEntry> plugins;

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getHomepageUrl() {
        return homepageUrl;
    }

    public List<PluginStoreEntry> getPlugins() {
        return plugins;
    }

    public static class PluginStoreEntry {

        @SerializedName("id")
        private String id;

        @SerializedName("name")
        private String name;

        @SerializedName("author")
        private String author;

        @SerializedName("description")
        private String description;

        @SerializedName("manifestUrl")
        private String manifestUrl;

        @SerializedName("repository")
        private String repository;

        @SerializedName("homepage")
        private String homepage;

        @SerializedName("category")
        private String category;

        @SerializedName("tags")
        private List<String> tags;

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getAuthor() {
            return author;
        }

        public String getDescription() {
            return description;
        }

        public String getManifestUrl() {
            return manifestUrl;
        }

        public String getRepository() {
            return repository;
        }

        public String getHomepage() {
            return homepage;
        }

        public String getCategory() {
            return category;
        }

        public List<String> getTags() {
            return tags;
        }
    }
}

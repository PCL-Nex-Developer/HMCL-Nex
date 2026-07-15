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

import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.List;

/**
 * Plugin manifest (plugin.json) structure.
 */
public class PluginManifest {

    @SerializedName("id")
    private String id;

    @SerializedName("name")
    private String name;

    @SerializedName("version")
    private String version;

    @SerializedName("description")
    private String description = "";

    @SerializedName("author")
    private String author = "";

    @SerializedName("type")
    private PluginType type;

    @SerializedName("entrypoint")
    private String entrypoint;

    @SerializedName("dependencies")
    private List<String> dependencies = Collections.emptyList();

    @SerializedName("minLauncherVersion")
    private String minLauncherVersion = "";

    public PluginManifest() {}

    public PluginManifest(String id, String name, String version, PluginType type, String entrypoint) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.type = type;
        this.entrypoint = entrypoint;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public String getDescription() {
        return description;
    }

    public String getAuthor() {
        return author;
    }

    public PluginType getType() {
        return type;
    }

    public String getEntrypoint() {
        return entrypoint;
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    public String getMinLauncherVersion() {
        return minLauncherVersion;
    }

    public static PluginManifest fromJson(Reader reader) throws IOException, JsonParseException {
        return JsonUtils.GSON.fromJson(reader, PluginManifest.class);
    }

    public enum PluginType {
        @SerializedName("java")
        JAVA,

        @SerializedName("kotlin")
        KOTLIN,

        @SerializedName("javascript")
        JAVASCRIPT
    }
}

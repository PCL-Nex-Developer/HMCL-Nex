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

import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/// Verifies package transport behavior that is specific to the plugin store.
@NotNullByDefault
public final class PluginStoreManagerTest {
    /// GitHub Releases and common CDNs redirect stable download URLs to a generated asset URL.
    @Test
    public void followsRedirectWhenDownloadingPackage(@TempDir Path temporaryDirectory) throws Exception {
        byte[] packageBytes = createPluginPackage();
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/download", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/asset");
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_MOVED_TEMP, -1);
            exchange.close();
        });
        server.createContext("/asset", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, packageBytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(packageBytes);
            }
        });
        server.start();

        try {
            String packageUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/download";
            PluginStoreManifest manifest = JsonUtils.GSON.fromJson("""
                    {
                      "schemaVersion": 1,
                      "id": "dev.hmclnex.test.redirect",
                      "versions": [
                        {
                          "version": "1.0.0",
                          "packageUrl": "%s",
                          "sha256": "%s",
                          "pluginApiVersion": 2,
                          "size": %d
                        }
                      ]
                    }
                    """.formatted(packageUrl, sha256(packageBytes), packageBytes.length),
                    PluginStoreManifest.class);
            assertNotNull(manifest);
            manifest.validate("dev.hmclnex.test.redirect");
            PluginStoreManifest.PluginVersionEntry version = manifest.getLatestVersion();
            assertNotNull(version);

            Path installed = new PluginStoreManager().downloadPlugin(
                    "dev.hmclnex.test.redirect",
                    version,
                    temporaryDirectory.resolve("plugins")
            );

            assertArrayEquals(packageBytes, Files.readAllBytes(installed));
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    /// Creates the smallest package that passes the store's identity and API checks.
    private static byte[] createPluginPackage() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("plugin.json"));
            zip.write("""
                    {
                      "schemaVersion": 2,
                      "id": "dev.hmclnex.test.redirect",
                      "name": "Redirect Test Plugin",
                      "version": "1.0.0",
                      "type": "java",
                      "entrypoint": "dev.hmclnex.test.RedirectPlugin",
                      "dependencies": []
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /// Returns the lower-case digest format used by repository manifests.
    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

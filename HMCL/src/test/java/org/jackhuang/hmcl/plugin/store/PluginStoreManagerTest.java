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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies store preferences, bounded text transport, exact-version staging, and atomic package validation.
@NotNullByDefault
public final class PluginStoreManagerTest {
    /// README size boundary mirrored from the store transport contract.
    private static final int README_LIMIT_BYTES = 2 * 1024 * 1024;

    /// Timeout for deterministic concurrency coordination in registry request tests.
    private static final long REQUEST_TIMEOUT_SECONDS = 5;

    /// Prevents a late registry request from persisting over a newer source selection.
    @Test
    public void lateRegistryRequestCannotOverwriteNewerCommittedSource(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        CountDownLatch oldRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseOldRequest = new CountDownLatch(1);
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/old", exchange -> {
            oldRequestStarted.countDown();
            awaitLatch(releaseOldRequest, "old registry release");
            respond(exchange, registry("Old Store"));
        });
        server.createContext("/new", exchange -> respond(exchange, registry("New Store")));
        server.setExecutor(serverExecutor);
        server.start();

        AtomicReference<@Nullable Throwable> oldRequestFailure = new AtomicReference<>();
        PluginStoreManager oldRequestManager = new PluginStoreManager(temporaryDirectory);
        Thread oldRequestThread = new Thread(() -> {
            try {
                oldRequestManager.loadRegistryForRequest(baseUrl + "/old");
            } catch (Throwable exception) {
                oldRequestFailure.set(exception);
            }
        }, "plugin-store-old-registry-request");

        try {
            oldRequestThread.start();
            assertTrue(oldRequestStarted.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));

            PluginStoreManager newRequestManager = new PluginStoreManager(temporaryDirectory);
            newRequestManager.loadRegistryForRequest(baseUrl + "/new");
            newRequestManager.commitActiveRegistry();
            assertEquals(baseUrl + "/new", new PluginStoreManager(temporaryDirectory).getRegistryUrl());

            releaseOldRequest.countDown();
            oldRequestThread.join(TimeUnit.SECONDS.toMillis(REQUEST_TIMEOUT_SECONDS));

            assertFalse(oldRequestThread.isAlive());
            assertNull(oldRequestFailure.get());
            assertEquals(baseUrl + "/old", oldRequestManager.getRegistryUrl());
            assertEquals(baseUrl + "/new", new PluginStoreManager(temporaryDirectory).getRegistryUrl());
        } finally {
            releaseOldRequest.countDown();
            oldRequestThread.join(TimeUnit.SECONDS.toMillis(REQUEST_TIMEOUT_SECONDS));
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    /// GitHub Releases and common CDNs redirect stable download URLs to a generated asset URL.
    @Test
    public void followsRedirectWhenDownloadingPackage(@TempDir Path temporaryDirectory) throws Exception {
        byte @Unmodifiable [] packageBytes = createPluginPackage(
                "dev.hmclnex.test.redirect",
                "1.0.0",
                4,
                "[]",
                "[]"
        );
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/download", exchange -> {
            requests.incrementAndGet();
            redirect(exchange, "/asset");
        });
        server.createContext("/asset", exchange -> {
            requests.incrementAndGet();
            respond(exchange, packageBytes);
        });
        server.start();

        try {
            String packageUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/download";
            PluginStoreManifest manifest = parseManifest("dev.hmclnex.test.redirect", """
                    {
                      "schemaVersion": 1,
                      "id": "dev.hmclnex.test.redirect",
                      "versions": [
                        {
                          "version": "1.0.0",
                          "packageUrl": "%s",
                          "sha256": "%s",
                          "pluginApiVersion": 4,
                          "permissions": [],
                          "requiredPermissions": [],
                          "launcherVersion": "*",
                          "dependencies": [],
                          "size": %d
                        }
                      ]
                    }
                    """.formatted(packageUrl, sha256(packageBytes), packageBytes.length));
            PluginStoreManifest.PluginVersionEntry version = Objects.requireNonNull(manifest.getLatestVersion());

            Path installed = new PluginStoreManager(temporaryDirectory).downloadPlugin(
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

    /// Keeps historical API-v1 through API-v3 metadata visible while excluding every old package from installation.
    @Test
    public void rejectsEveryPluginApiBeforeVersionFour(@TempDir Path temporaryDirectory) throws Exception {
        String pluginId = "dev.hmclnex.test.legacy-api";
        PluginStoreManifest manifest = parseManifest(pluginId, """
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "versions": [
                    {
                      "version": "1.0.0",
                      "packageUrl": "https://example.com/v1.npl",
                      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                      "pluginApiVersion": 1,
                      "size": 1
                    },
                    {
                      "version": "2.0.0",
                      "packageUrl": "https://example.com/v2.npl",
                      "sha256": "1111111111111111111111111111111111111111111111111111111111111111",
                      "pluginApiVersion": 2,
                      "size": 1
                    },
                    {
                      "version": "3.0.0",
                      "packageUrl": "https://example.com/v3.npl",
                      "sha256": "2222222222222222222222222222222222222222222222222222222222222222",
                      "pluginApiVersion": 3,
                      "permissions": [],
                      "dependencies": [],
                      "size": 1
                    }
                  ]
                }
                """.formatted(pluginId));
        PluginStoreManager manager = new PluginStoreManager(temporaryDirectory);

        assertEquals(3, manifest.getVersionsNewestFirst().size());
        assertTrue(manager.getCompatibleVersions(manifest).isEmpty());
        for (PluginStoreManifest.PluginVersionEntry version : manifest.getVersions()) {
            IOException exception = assertThrows(IOException.class, () -> manager.validateCompatibility(version));
            assertTrue(exception.toString().contains("only supports plugin API 4"));
            assertFalse(manager.isCompatible(version));
        }
    }

    /// Persists favorite changes across managers and rejects IDs that cannot be stored safely.
    @Test
    public void persistFavoritesAndRejectInvalidIds(@TempDir Path temporaryDirectory) {
        String pluginId = "dev.hmclnex.test.favorite";
        PluginStoreManager manager = new PluginStoreManager(temporaryDirectory);

        manager.setFavorite(pluginId, true);
        assertTrue(manager.isFavorite(pluginId));
        assertEquals(Set.of(pluginId), manager.getFavoritePluginIds());

        PluginStoreManager reloaded = new PluginStoreManager(temporaryDirectory);
        assertTrue(reloaded.isFavorite(pluginId));
        assertThrows(IllegalArgumentException.class, () -> reloaded.setFavorite("invalid favorite id", true));
        assertEquals(Set.of(pluginId), reloaded.getFavoritePluginIds());

        reloaded.setFavorite(pluginId, false);
        assertFalse(new PluginStoreManager(temporaryDirectory).isFavorite(pluginId));
    }

    /// Reuses a cached README until clearing the manager caches forces a fresh request.
    @Test
    public void cacheReadmeUntilCacheIsCleared(@TempDir Path temporaryDirectory) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/readme", exchange -> {
            int requestNumber = requests.incrementAndGet();
            respond(exchange, ("README request " + requestNumber).getBytes(StandardCharsets.UTF_8));
        });
        server.start();

        try {
            String readmeUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/readme";
            PluginStoreManifest manifest = readmeManifest(readmeUrl);
            PluginStoreManager manager = new PluginStoreManager(temporaryDirectory);

            assertEquals("README request 1", manager.fetchReadme(manifest));
            assertEquals("README request 1", manager.fetchReadme(manifest));
            assertEquals(1, requests.get());

            manager.clearCache();
            assertEquals("README request 2", manager.fetchReadme(manifest));
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    /// Resolves every relative README redirect hop before returning the final Markdown body.
    @Test
    public void followRedirectsWhenFetchingReadme(@TempDir Path temporaryDirectory) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/readme", exchange -> {
            requests.incrementAndGet();
            redirect(exchange, "/readme-middle");
        });
        server.createContext("/readme-middle", exchange -> {
            requests.incrementAndGet();
            redirect(exchange, "/readme-content");
        });
        server.createContext("/readme-content", exchange -> {
            requests.incrementAndGet();
            respond(exchange, "# Redirected README".getBytes(StandardCharsets.UTF_8));
        });
        server.start();

        try {
            String readmeUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/readme";
            PluginStoreManager manager = new PluginStoreManager(temporaryDirectory);

            assertEquals("# Redirected README", manager.fetchReadme(readmeManifest(readmeUrl)));
            assertEquals(3, requests.get());
        } finally {
            server.stop(0);
        }
    }

    /// Rejects remote-to-loopback redirects while preserving loopback HTTP development chains.
    @Test
    public void enforceRedirectTargetPolicy() {
        assertThrows(
                IOException.class,
                () -> PluginStoreManager.validateRedirectTarget(
                        URI.create("https://example.com/readme"),
                        URI.create("http://127.0.0.1/x"),
                        "plugin README"
                )
        );
        assertDoesNotThrow(() -> PluginStoreManager.validateRedirectTarget(
                URI.create("http://127.0.0.1/readme"),
                URI.create("http://127.0.0.1/x"),
                "plugin README"
        ));
    }

    /// Accepts the exact README byte limit and rejects a chunked response that exceeds it.
    @Test
    public void enforceReadmeSizeLimit(@TempDir Path temporaryDirectory) throws Exception {
        byte @Unmodifiable [] maximumReadme = new byte[README_LIMIT_BYTES];
        byte @Unmodifiable [] oversizedReadme = new byte[README_LIMIT_BYTES + 1];
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/maximum", exchange -> respond(exchange, maximumReadme));
        server.createContext("/oversized", exchange -> respondChunked(exchange, oversizedReadme));
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            PluginStoreManager manager = new PluginStoreManager(temporaryDirectory);

            assertEquals(
                    README_LIMIT_BYTES,
                    manager.fetchReadme(readmeManifest(baseUrl + "/maximum")).length()
            );
            assertThrows(
                    IOException.class,
                    () -> manager.fetchReadme(readmeManifest(baseUrl + "/oversized"))
            );
        } finally {
            server.stop(0);
        }
    }

    /// Downloads the explicitly selected historical version into its deterministic staging path.
    @Test
    public void stageSelectedHistoricalVersion(@TempDir Path temporaryDirectory) throws Exception {
        String pluginId = "dev.hmclnex.test.staging";
        byte @Unmodifiable [] oldPackage = createPluginPackage(pluginId, "1.0.0", 4, "[]", "[]");
        byte @Unmodifiable [] latestPackage = createPluginPackage(pluginId, "2.0.0", 4, "[]", "[]");
        AtomicInteger oldRequests = new AtomicInteger();
        AtomicInteger latestRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/old", exchange -> {
            oldRequests.incrementAndGet();
            respond(exchange, oldPackage);
        });
        server.createContext("/latest", exchange -> {
            latestRequests.incrementAndGet();
            respond(exchange, latestPackage);
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            PluginStoreManifest manifest = parseManifest(pluginId, repositoryManifest(
                    pluginId,
                    repositoryVersion("1.0.0", baseUrl + "/old", oldPackage, "[]", "[]") + ","
                            + repositoryVersion("2.0.0", baseUrl + "/latest", latestPackage, "[]", "[]")
            ));
            PluginStoreManifest.PluginVersionEntry selected = Objects.requireNonNull(manifest.getVersion("1.0.0"));
            Path stagingDirectory = temporaryDirectory.resolve("staging");

            Path staged = new PluginStoreManager(temporaryDirectory).downloadPluginToStaging(
                    pluginId,
                    selected,
                    stagingDirectory
            );

            assertEquals(
                    pluginId + "-" + sha256(oldPackage).substring(0, 12) + ".npl",
                    staged.getFileName().toString()
            );
            assertArrayEquals(oldPackage, Files.readAllBytes(staged));
            assertEquals(1, oldRequests.get());
            assertEquals(0, latestRequests.get());
        } finally {
            server.stop(0);
        }
    }

    /// Leaves an existing installed package untouched for identity, version, schema, permission, or dependency drift.
    @Test
    public void preserveExistingTargetWhenPackageMetadataDiffers(@TempDir Path temporaryDirectory) throws Exception {
        String pluginId = "dev.hmclnex.test.metadata";
        String selectedVersion = "2.0.0";
        AtomicReference<byte @Unmodifiable []> responseBody = new AtomicReference<>(new byte[0]);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/package", exchange -> respond(exchange, responseBody.get()));
        server.start();

        try {
            String packageUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/package";
            Path installedPackage = temporaryDirectory.resolve("plugins").resolve(pluginId + ".npl");
            Files.createDirectories(installedPackage.getParent());
            byte @Unmodifiable [] existingPackage = "existing verified package".getBytes(StandardCharsets.UTF_8);
            Files.write(installedPackage, existingPackage);
            PluginStoreManager manager = new PluginStoreManager(temporaryDirectory);

            assertMetadataMismatchPreservesTarget(
                    manager,
                    responseBody,
                    packageUrl,
                    pluginId,
                    selectedVersion,
                    installedPackage,
                    existingPackage,
                    createPluginPackage("dev.hmclnex.test.other", selectedVersion, 4, "[]", "[]"),
                    "does not match registry entry"
            );
            assertMetadataMismatchPreservesTarget(
                    manager,
                    responseBody,
                    packageUrl,
                    pluginId,
                    selectedVersion,
                    installedPackage,
                    existingPackage,
                    createPluginPackage(pluginId, "9.0.0", 4, "[]", "[]"),
                    "does not match selected version"
            );
            assertMetadataMismatchPreservesTarget(
                    manager,
                    responseBody,
                    packageUrl,
                    pluginId,
                    selectedVersion,
                    installedPackage,
                    existingPackage,
                    createPluginPackage(pluginId, selectedVersion, 2, "[]", "[]"),
                    "does not match pluginApiVersion"
            );
            assertMetadataMismatchPreservesTarget(
                    manager,
                    responseBody,
                    packageUrl,
                    pluginId,
                    selectedVersion,
                    installedPackage,
                    existingPackage,
                    createPluginPackage(pluginId, selectedVersion, 4, "[\"network\"]", "[]"),
                    "permissions do not match"
            );
            assertMetadataMismatchPreservesTarget(
                    manager,
                    responseBody,
                    packageUrl,
                    pluginId,
                    selectedVersion,
                    installedPackage,
                    existingPackage,
                    createPluginPackage(
                            pluginId,
                            selectedVersion,
                            4,
                            "[]",
                            "[{\"id\":\"dev.hmclnex.test.metadata-base\",\"version\":\">=1.0.0\"}]"
                    ),
                    "dependencies do not match"
            );
            byte @Unmodifiable [] requiredMismatch = createPluginPackage(
                    pluginId,
                    selectedVersion,
                    4,
                    "[\"filesystem\",\"network\"]",
                    "[]",
                    "[\"network\"]",
                    "*"
            );
            assertMetadataMismatchPreservesTarget(
                    manager,
                    responseBody,
                    packageUrl,
                    pluginId,
                    selectedVersion,
                    installedPackage,
                    existingPackage,
                    requiredMismatch,
                    repositoryVersionFour(
                            selectedVersion,
                            packageUrl,
                            requiredMismatch,
                            "[\"filesystem\",\"network\"]",
                            "[\"filesystem\"]",
                            "*",
                            "[]"
                    ),
                    "requiredPermissions do not match"
            );
            byte @Unmodifiable [] launcherMismatch = createPluginPackage(
                    pluginId,
                    selectedVersion,
                    4,
                    "[]",
                    "[]",
                    "[]",
                    ">=9999"
            );
            assertMetadataMismatchPreservesTarget(
                    manager,
                    responseBody,
                    packageUrl,
                    pluginId,
                    selectedVersion,
                    installedPackage,
                    existingPackage,
                    launcherMismatch,
                    repositoryVersionFour(
                            selectedVersion,
                            packageUrl,
                            launcherMismatch,
                            "[]",
                            "[]",
                            "*",
                            "[]"
                    ),
                    "launcherVersion does not match"
            );
        } finally {
            server.stop(0);
        }
    }

    /// Downloads one invalid package and verifies that validation fails before replacing the installed target.
    ///
    /// @param manager store manager under test
    /// @param responseBody body served by the package endpoint
    /// @param packageUrl local package endpoint
    /// @param pluginId expected plugin ID
    /// @param selectedVersion selected repository version
    /// @param installedPackage existing installed package path
    /// @param existingPackage original package bytes
    /// @param candidatePackage downloaded package with mismatched metadata
    /// @param expectedMessage diagnostic fragment identifying the metadata mismatch
    /// @throws Exception if fixture creation or target inspection fails unexpectedly
    private static void assertMetadataMismatchPreservesTarget(
            PluginStoreManager manager,
            AtomicReference<byte @Unmodifiable []> responseBody,
            String packageUrl,
            String pluginId,
            String selectedVersion,
            Path installedPackage,
            byte @Unmodifiable [] existingPackage,
            byte @Unmodifiable [] candidatePackage,
            String expectedMessage
    ) throws Exception {
        assertMetadataMismatchPreservesTarget(
                manager,
                responseBody,
                packageUrl,
                pluginId,
                selectedVersion,
                installedPackage,
                existingPackage,
                candidatePackage,
                repositoryVersion(selectedVersion, packageUrl, candidatePackage, "[]", "[]"),
                expectedMessage
        );
    }

    /// Downloads one invalid package against explicit remote metadata and preserves the installed target.
    ///
    /// @param manager store manager under test
    /// @param responseBody body served by the package endpoint
    /// @param packageUrl local package endpoint
    /// @param pluginId expected plugin ID
    /// @param selectedVersion selected repository version
    /// @param installedPackage existing installed package path
    /// @param existingPackage original package bytes
    /// @param candidatePackage downloaded package with mismatched metadata
    /// @param repositoryVersionJson exact remote version metadata
    /// @param expectedMessage diagnostic fragment identifying the metadata mismatch
    /// @throws Exception if fixture creation or target inspection fails unexpectedly
    private static void assertMetadataMismatchPreservesTarget(
            PluginStoreManager manager,
            AtomicReference<byte @Unmodifiable []> responseBody,
            String packageUrl,
            String pluginId,
            String selectedVersion,
            Path installedPackage,
            byte @Unmodifiable [] existingPackage,
            byte @Unmodifiable [] candidatePackage,
            String repositoryVersionJson,
            String expectedMessage
    ) throws Exception {
        responseBody.set(candidatePackage);
        PluginStoreManifest manifest = parseManifest(pluginId, repositoryManifest(
                pluginId,
                repositoryVersionJson
        ));
        PluginStoreManifest.PluginVersionEntry version = Objects.requireNonNull(
                manifest.getVersion(selectedVersion)
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> manager.downloadPlugin(pluginId, version, installedPackage.getParent())
        );
        assertTrue(exception.getMessage().contains(expectedMessage), exception.getMessage());
        assertArrayEquals(existingPackage, Files.readAllBytes(installedPackage));
    }

    /// Creates a minimal validated repository manifest that points at one README URL.
    ///
    /// @param readmeUrl README endpoint
    /// @return validated repository manifest
    /// @throws IOException if the generated fixture is invalid
    private static PluginStoreManifest readmeManifest(String readmeUrl) throws IOException {
        return parseManifest("dev.hmclnex.test.readme", """
                {
                  "schemaVersion": 1,
                  "id": "dev.hmclnex.test.readme",
                  "readmeUrl": "%s",
                  "versions": [
                    {
                      "version": "1.0.0",
                      "packageUrl": "https://example.com/plugin.npl",
                      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                      "pluginApiVersion": 2,
                      "size": 1
                    }
                  ]
                }
                """.formatted(readmeUrl));
    }

    /// Creates one valid empty registry response with the supplied display name.
    ///
    /// @param name registry display name
    /// @return serialized UTF-8 registry body
    private static byte @Unmodifiable [] registry(String name) {
        return """
                {
                  "schemaVersion": 1,
                  "name": "%s",
                  "plugins": []
                }
                """.formatted(name).getBytes(StandardCharsets.UTF_8);
    }

    /// Awaits a concurrency gate with a bounded timeout suitable for an HTTP handler.
    ///
    /// @param latch gate to await
    /// @param description gate description used in diagnostics
    /// @throws IOException if the gate times out or the handler thread is interrupted
    private static void awaitLatch(CountDownLatch latch, String description) throws IOException {
        try {
            if (!latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for " + description);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + description, exception);
        }
    }

    /// Creates one schema-v2 repository manifest around serialized version entries.
    ///
    /// @param pluginId repository plugin ID
    /// @param versionsJson one or more comma-separated version objects
    /// @return repository manifest JSON
    private static String repositoryManifest(String pluginId, String versionsJson) {
        return """
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "versions": [%s]
                }
                """.formatted(pluginId, versionsJson);
    }

    /// Creates one API-v4 repository version entry with no required permissions and unrestricted launcher support.
    ///
    /// @param version selected version string
    /// @param packageUrl package endpoint
    /// @param packageBytes exact package body
    /// @param permissionsJson permission array JSON
    /// @param dependenciesJson dependency array JSON
    /// @return serialized repository version entry
    /// @throws NoSuchAlgorithmException if SHA-256 is unavailable
    private static String repositoryVersion(
            String version,
            String packageUrl,
            byte @Unmodifiable [] packageBytes,
            String permissionsJson,
            String dependenciesJson
    ) throws NoSuchAlgorithmException {
        return """
                {
                  "version": "%s",
                  "packageUrl": "%s",
                  "sha256": "%s",
                  "pluginApiVersion": 4,
                  "permissions": %s,
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "dependencies": %s,
                  "size": %d
                }
                """.formatted(
                version,
                packageUrl,
                sha256(packageBytes),
                permissionsJson,
                dependenciesJson,
                packageBytes.length
        );
    }

    /// Creates one API-v4 repository version entry with exact package policy metadata.
    ///
    /// @param version selected version string
    /// @param packageUrl package endpoint
    /// @param packageBytes exact package body
    /// @param permissionsJson complete permission array JSON
    /// @param requiredPermissionsJson required permission array JSON
    /// @param launcherVersion launcher version constraint
    /// @param dependenciesJson dependency array JSON
    /// @return serialized repository version entry
    /// @throws NoSuchAlgorithmException if SHA-256 is unavailable
    private static String repositoryVersionFour(
            String version,
            String packageUrl,
            byte @Unmodifiable [] packageBytes,
            String permissionsJson,
            String requiredPermissionsJson,
            String launcherVersion,
            String dependenciesJson
    ) throws NoSuchAlgorithmException {
        return """
                {
                  "version": "%s",
                  "packageUrl": "%s",
                  "sha256": "%s",
                  "pluginApiVersion": 4,
                  "permissions": %s,
                  "requiredPermissions": %s,
                  "launcherVersion": "%s",
                  "dependencies": %s,
                  "size": %d
                }
                """.formatted(
                version,
                packageUrl,
                sha256(packageBytes),
                permissionsJson,
                requiredPermissionsJson,
                launcherVersion,
                dependenciesJson,
                packageBytes.length
        );
    }

    /// Parses and validates one repository manifest fixture.
    ///
    /// @param expectedPluginId plugin ID bound to the repository
    /// @param json repository manifest JSON
    /// @return validated repository manifest
    /// @throws IOException if the fixture violates repository validation
    private static PluginStoreManifest parseManifest(String expectedPluginId, String json) throws IOException {
        PluginStoreManifest manifest = Objects.requireNonNull(
                JsonUtils.GSON.fromJson(json, PluginStoreManifest.class),
                "Generated repository manifest was null"
        );
        manifest.validate(expectedPluginId);
        return manifest;
    }

    /// Creates the smallest package that passes package-manifest parsing for the requested schema.
    ///
    /// @param pluginId package plugin ID
    /// @param version package version
    /// @param schemaVersion package manifest schema version
    /// @param permissionsJson permission array used by schema-v3 packages
    /// @param dependenciesJson dependency array JSON
    /// @return complete `.npl` package bytes
    /// @throws IOException if ZIP creation fails
    private static byte @Unmodifiable [] createPluginPackage(
            String pluginId,
            String version,
            int schemaVersion,
            String permissionsJson,
            String dependenciesJson
    ) throws IOException {
        return createPluginPackage(
                pluginId,
                version,
                schemaVersion,
                permissionsJson,
                dependenciesJson,
                "[]",
                "*"
        );
    }

    /// Creates the smallest package with caller-selected schema-v4 security and launcher metadata.
    ///
    /// @param pluginId package plugin ID
    /// @param version package version
    /// @param schemaVersion package manifest schema version
    /// @param permissionsJson complete permission array JSON
    /// @param dependenciesJson dependency array JSON
    /// @param requiredPermissionsJson required permission array JSON for schema-v4 packages
    /// @param launcherVersion launcher constraint for schema-v4 packages
    /// @return complete `.npl` package bytes
    /// @throws IOException if ZIP creation fails
    private static byte @Unmodifiable [] createPluginPackage(
            String pluginId,
            String version,
            int schemaVersion,
            String permissionsJson,
            String dependenciesJson,
            String requiredPermissionsJson,
            String launcherVersion
    ) throws IOException {
        String permissionDeclaration = schemaVersion >= 3
                ? "\"permissions\": " + permissionsJson + ","
                : "";
        String schemaFourDeclarations = schemaVersion >= 4
                ? "\"requiredPermissions\": " + requiredPermissionsJson + ",\n"
                + "      \"launcherVersion\": \"" + launcherVersion + "\","
                : "";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("plugin.json"));
            zip.write("""
                    {
                      "schemaVersion": %d,
                      "id": "%s",
                      "name": "Plugin Store Test",
                      "version": "%s",
                      "type": "java",
                      "entrypoint": "dev.hmclnex.test.Plugin",
                      %s
                      %s
                      "dependencies": %s
                    }
                    """.formatted(
                    schemaVersion,
                    pluginId,
                    version,
                    permissionDeclaration,
                    schemaFourDeclarations,
                    dependenciesJson
            ).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /// Writes one HTTP response with an exact content length.
    ///
    /// @param exchange incoming HTTP exchange
    /// @param body response body
    /// @throws IOException if the response cannot be written
    private static void respond(HttpExchange exchange, byte @Unmodifiable [] body) throws IOException {
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    /// Writes one HTTP response using chunked transfer encoding.
    ///
    /// @param exchange incoming HTTP exchange
    /// @param body response body
    /// @throws IOException if the response cannot be written
    private static void respondChunked(HttpExchange exchange, byte @Unmodifiable [] body) throws IOException {
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    /// Writes one relative temporary redirect without a response body.
    ///
    /// @param exchange incoming HTTP exchange
    /// @param location relative redirect target
    /// @throws IOException if the redirect cannot be written
    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_MOVED_TEMP, -1);
        exchange.close();
    }

    /// Returns the lower-case digest format used by repository manifests.
    ///
    /// @param bytes content to hash
    /// @return lower-case SHA-256 digest
    /// @throws NoSuchAlgorithmException if SHA-256 is unavailable
    private static String sha256(byte @Unmodifiable [] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

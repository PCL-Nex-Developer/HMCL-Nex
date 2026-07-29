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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies complete-graph dependency selection for plugin-store installation plans.
@NotNullByDefault
public final class PluginStoreDependencyResolverTest {
    /// Requires a fresh decision for updates with unchanged or empty declarations, but never for a reused artifact.
    @Test
    public void changedArtifactsAlwaysRequireFreshPermissionReview() throws IOException {
        PluginManifest unchangedInstalled = packageManifestWithPermissions(
                "dev.test.update.unchanged",
                "1.0.0",
                "[\"network\"]"
        );
        PluginManifest emptyInstalled = packageManifestWithPermissions(
                "dev.test.update.empty",
                "1.0.0",
                "[]"
        );
        PluginManifest reusedInstalled = packageManifestWithPermissions(
                "dev.test.reuse",
                "1.0.0",
                "[\"filesystem\"]"
        );
        PluginInstallPlan.Entry installation = new PluginInstallPlan.Entry(
                "dev.test.install",
                "Install",
                "1.0.0",
                PluginInstallPlan.Action.INSTALL,
                null,
                remoteVersion("dev.test.install", "1.0.0", "1", "[\"clipboard\"]"),
                null
        );
        PluginInstallPlan.Entry unchangedUpdate = new PluginInstallPlan.Entry(
                "dev.test.update.unchanged",
                "Unchanged Update",
                "1.0.0",
                PluginInstallPlan.Action.UPDATE,
                null,
                remoteVersion("dev.test.update.unchanged", "1.0.0", "2", "[\"network\"]"),
                unchangedInstalled
        );
        PluginInstallPlan.Entry emptyUpdate = new PluginInstallPlan.Entry(
                "dev.test.update.empty",
                "Empty Update",
                "1.0.0",
                PluginInstallPlan.Action.UPDATE,
                null,
                remoteVersion("dev.test.update.empty", "1.0.0", "3", "[]"),
                emptyInstalled
        );
        PluginInstallPlan.Entry reuse = new PluginInstallPlan.Entry(
                "dev.test.reuse",
                "Reuse",
                "1.0.0",
                PluginInstallPlan.Action.REUSE,
                null,
                null,
                reusedInstalled
        );
        PluginInstallPlan plan = new PluginInstallPlan(
                "dev.test.update.unchanged",
                List.of(reuse, installation, unchangedUpdate, emptyUpdate),
                Map.of(
                        "dev.test.reuse",
                        new PluginArtifactIdentity("dev.test.reuse", "1.0.0", "f".repeat(64))
                ),
                Map.of(
                        "dev.test.install",
                        Optional.empty(),
                        "dev.test.update.unchanged",
                        Optional.of(new PluginArtifactIdentity(
                                "dev.test.update.unchanged",
                                "1.0.0",
                                "d".repeat(64)
                        )),
                        "dev.test.update.empty",
                        Optional.of(new PluginArtifactIdentity(
                                "dev.test.update.empty",
                                "1.0.0",
                                "e".repeat(64)
                        ))
                )
        );

        assertTrue(installation.requiresFreshPermissionReview());
        assertTrue(unchangedUpdate.requiresFreshPermissionReview());
        assertTrue(emptyUpdate.requiresFreshPermissionReview());
        assertFalse(reuse.requiresFreshPermissionReview());
        assertEquals(List.of(installation, unchangedUpdate, emptyUpdate), plan.getPermissionReviewEntries());
        assertEquals(List.of(PluginPermission.NETWORK), unchangedUpdate.getPermissions());
        assertTrue(emptyUpdate.getPermissions().isEmpty());
    }

    /// Treats a selected remote artifact as an update even when its version string matches the installed package.
    @Test
    public void sameVersionDifferentShaStillResolvesAsUpdate(@TempDir Path temporaryDirectory) throws Exception {
        String pluginId = "dev.test.same-version-update";
        String previousArtifactSha256 = "a".repeat(64);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/registry", exchange -> respond(exchange, """
                {
                  "schemaVersion": 1,
                  "name": "Same Version Update Store",
                  "plugins": [
                    {"id":"%s","name":"Same Version","manifestUrl":"%s/plugin"}
                  ]
                }
                """.formatted(pluginId, baseUrl)));
        server.createContext("/plugin", exchange -> respond(exchange, repositoryManifest(
                pluginId,
                version(baseUrl, "plugin", "1.0.0", "b", "[]")
        )));
        server.start();

        try {
            PluginStoreManager manager = new PluginStoreManager();
            manager.loadSource(new PluginSource("test", baseUrl + "/registry", null, true, false));
            PluginStoreManifest manifest = manager.getPluginManifest(pluginId, baseUrl + "/plugin");
            PluginStoreManifest.PluginVersionEntry selectedVersion = manifest.getVersion("1.0.0");
            assertNotNull(selectedVersion);
            @Unmodifiable Map<String, PluginManifest> installed = Map.of(
                    pluginId,
                    packageManifest(pluginId, "1.0.0", "[]")
            );
            PluginArtifactIdentity previousIdentity = new PluginArtifactIdentity(
                    pluginId,
                    "1.0.0",
                    previousArtifactSha256
            );

            PluginInstallPlan plan = manager.resolveInstallPlan(
                    pluginId,
                    selectedVersion,
                    installed,
                    Map.of(pluginId, previousIdentity),
                    Map.of()
            );
            PluginInstallPlan.Entry root = plan.getRootEntry();

            assertEquals(PluginInstallPlan.Action.UPDATE, root.getAction());
            assertEquals("1.0.0", root.getVersion());
            assertNotNull(root.getRemoteVersion());
            assertNotEquals(previousArtifactSha256, root.getRemoteVersion().getSha256());
            assertEquals(List.of(root), plan.getPermissionReviewEntries());
            assertEquals(
                    Map.of(pluginId, Optional.of(previousIdentity)),
                    plan.getExpectedPriorArtifacts()
            );
        } finally {
            server.stop(0);
        }
    }

    /// Reuses an installed dependency only when the caller explicitly confirms its exact artifact permission state.
    @Test
    public void installedDependencyRequiresExplicitReuseEligibility(@TempDir Path temporaryDirectory) throws Exception {
        String rootId = "dev.test.permission-root";
        String dependencyId = "dev.test.permission-dependency";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/registry", exchange -> respond(exchange, """
                {
                  "schemaVersion": 1,
                  "name": "Permission-Aware Reuse Store",
                  "plugins": [
                    {"id":"%s","name":"Root","manifestUrl":"%s/root"},
                    {"id":"%s","name":"Dependency","manifestUrl":"%s/dependency"}
                  ]
                }
                """.formatted(rootId, baseUrl, dependencyId, baseUrl)));
        server.createContext("/root", exchange -> respond(exchange, repositoryManifest(
                rootId,
                version(baseUrl, "root", "1.0.0", "7", """
                        [{"id":"%s","version":"1.0.0"}]
                        """.formatted(dependencyId))
        )));
        server.createContext("/dependency", exchange -> respond(exchange, repositoryManifest(
                dependencyId,
                version(baseUrl, "dependency", "1.0.0", "8", "[]")
        )));
        server.start();

        try {
            PluginStoreManager manager = new PluginStoreManager();
            manager.loadSource(new PluginSource("test", baseUrl + "/registry", null, true, false));
            PluginStoreManifest rootManifest = manager.getPluginManifest(rootId, baseUrl + "/root");
            PluginStoreManifest.PluginVersionEntry rootVersion = rootManifest.getVersion("1.0.0");
            assertNotNull(rootVersion);
            @Unmodifiable Map<String, PluginManifest> installed = Map.of(
                    dependencyId,
                    packageManifest(dependencyId, "1.0.0", "[]")
            );
            PluginArtifactIdentity dependencyIdentity = new PluginArtifactIdentity(
                    dependencyId,
                    "1.0.0",
                    "a".repeat(64)
            );

            PluginInstallPlan deniedReuse = manager.resolveInstallPlan(
                    rootId,
                    rootVersion,
                    installed,
                    Map.of(dependencyId, dependencyIdentity),
                    Map.of()
            );
            PluginInstallPlan approvedReuse = manager.resolveInstallPlan(
                    rootId,
                    rootVersion,
                    installed,
                    Map.of(dependencyId, dependencyIdentity),
                    Map.of(dependencyId, dependencyIdentity)
            );

            assertEquals(PluginInstallPlan.Action.UPDATE, deniedReuse.getEntries().get(0).getAction());
            assertEquals(
                    List.of(dependencyId, rootId),
                    deniedReuse.getPermissionReviewEntries().stream()
                            .map(PluginInstallPlan.Entry::getPluginId)
                            .toList()
            );
            assertEquals(PluginInstallPlan.Action.REUSE, approvedReuse.getEntries().get(0).getAction());
            assertEquals(
                    Map.of(dependencyId, dependencyIdentity),
                    approvedReuse.getReusableArtifactIdentities()
            );
            assertEquals(
                    List.of(rootId),
                    approvedReuse.getPermissionReviewEntries().stream()
                            .map(PluginInstallPlan.Entry::getPluginId)
                            .toList()
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.resolveInstallPlan(rootId, rootVersion, installed)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.resolveInstallPlan(rootId, rootVersion, installed, Set.of(dependencyId))
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.resolveInstallPlan(
                            rootId,
                            rootVersion,
                            installed,
                            Map.of(dependencyId, dependencyIdentity),
                            Map.of(
                                    dependencyId,
                                    new PluginArtifactIdentity(dependencyId, "2.0.0", "b".repeat(64))
                            )
                    )
            );

            @Unmodifiable Map<String, PluginManifest> legacyInstalled = Map.of(
                    dependencyId,
                    legacyPackageManifest(dependencyId, "1.0.0", "[]")
            );
            PluginArtifactIdentity legacyIdentity = new PluginArtifactIdentity(
                    dependencyId,
                    "1.0.0",
                    "e".repeat(64)
            );
            PluginInstallPlan legacyPlan = manager.resolveInstallPlan(
                    rootId,
                    rootVersion,
                    legacyInstalled,
                    Map.of(dependencyId, legacyIdentity),
                    Map.of(dependencyId, legacyIdentity)
            );

            assertEquals(PluginInstallPlan.Action.UPDATE, legacyPlan.getEntries().get(0).getAction());
            assertTrue(legacyPlan.getReusableArtifactIdentities().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    /// Fails clearly when an unauthorized installed dependency has no remote artifact for a fresh review.
    @Test
    public void unauthorizedReuseWithoutRemotePackageFailsExplicitly(@TempDir Path temporaryDirectory)
            throws Exception {
        String rootId = "dev.test.unpublished-root";
        String dependencyId = "dev.test.unpublished-dependency";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/registry", exchange -> respond(exchange, """
                {
                  "schemaVersion": 1,
                  "name": "Unpublished Dependency Store",
                  "plugins": [
                    {"id":"%s","name":"Root","manifestUrl":"%s/root"}
                  ]
                }
                """.formatted(rootId, baseUrl)));
        server.createContext("/root", exchange -> respond(exchange, repositoryManifest(
                rootId,
                version(baseUrl, "root", "1.0.0", "9", """
                        [{"id":"%s","version":"1.0.0"}]
                        """.formatted(dependencyId))
        )));
        server.start();

        try {
            PluginStoreManager manager = new PluginStoreManager();
            manager.loadSource(new PluginSource("test", baseUrl + "/registry", null, true, false));
            PluginStoreManifest rootManifest = manager.getPluginManifest(rootId, baseUrl + "/root");
            PluginStoreManifest.PluginVersionEntry rootVersion = rootManifest.getVersion("1.0.0");
            assertNotNull(rootVersion);
            @Unmodifiable Map<String, PluginManifest> installed = Map.of(
                    dependencyId,
                    packageManifest(dependencyId, "1.0.0", "[]")
            );
            PluginArtifactIdentity dependencyIdentity = new PluginArtifactIdentity(
                    dependencyId,
                    "1.0.0",
                    "c".repeat(64)
            );

            IOException failure = assertThrows(
                    IOException.class,
                    () -> manager.resolveInstallPlan(
                            rootId,
                            rootVersion,
                            installed,
                            Map.of(dependencyId, dependencyIdentity),
                            Map.of()
                    )
            );
            PluginInstallPlan approved = manager.resolveInstallPlan(
                    rootId,
                    rootVersion,
                    installed,
                    Map.of(dependencyId, dependencyIdentity),
                    Map.of(dependencyId, dependencyIdentity)
            );

            assertTrue(Objects.requireNonNull(failure.getMessage()).contains("cannot be reused"));
            assertEquals(PluginInstallPlan.Action.REUSE, approved.getEntries().get(0).getAction());
        } finally {
            server.stop(0);
        }
    }

    /// Selects a lower shared dependency version when a later sibling contributes an additional compatible range.
    @Test
    public void backtracksToSharedVersionSatisfyingAllConstraints(@TempDir Path temporaryDirectory) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/registry", exchange -> respond(exchange, """
                {
                  "schemaVersion": 1,
                  "name": "Dependency Test Store",
                  "plugins": [
                    {"id":"dev.test.root","name":"Root","manifestUrl":"%s/root"},
                    {"id":"dev.test.bridge","name":"Bridge","manifestUrl":"%s/bridge"},
                    {"id":"dev.test.base","name":"Base","manifestUrl":"%s/base"}
                  ]
                }
                """.formatted(baseUrl, baseUrl, baseUrl)));
        server.createContext("/root", exchange -> respond(exchange, repositoryManifest(
                "dev.test.root",
                version(baseUrl, "root", "1.0.0", "0", """
                        [
                          {"id":"dev.test.base","version":">=1.0.0 <3.0.0"},
                          {"id":"dev.test.bridge","version":"1.0.0"}
                        ]
                        """)
        )));
        server.createContext("/bridge", exchange -> respond(exchange, repositoryManifest(
                "dev.test.bridge",
                version(baseUrl, "bridge", "1.0.0", "1", """
                        [{"id":"dev.test.base","version":"<2.0.0"}]
                        """)
        )));
        server.createContext("/base", exchange -> respond(exchange, repositoryManifest(
                "dev.test.base",
                version(baseUrl, "base", "2.5.0", "2", "[]") + ","
                        + version(baseUrl, "base", "1.5.0", "3", "[]")
        )));
        server.start();

        try {
            PluginStoreManager manager = new PluginStoreManager();
            manager.loadSource(new PluginSource("test", baseUrl + "/registry", null, true, false));
            PluginStoreManifest rootManifest = manager.getPluginManifest("dev.test.root", baseUrl + "/root");
            PluginStoreManifest.PluginVersionEntry rootVersion = rootManifest.getVersion("1.0.0");
            assertNotNull(rootVersion);

            PluginInstallPlan plan = manager.resolveInstallPlan("dev.test.root", rootVersion, Map.of());

            assertEquals(
                    List.of("dev.test.base", "dev.test.bridge", "dev.test.root"),
                    plan.getEntries().stream().map(PluginInstallPlan.Entry::getPluginId).toList()
            );
            assertEquals("1.5.0", plan.getEntries().get(0).getVersion());
        } finally {
            server.stop(0);
        }
    }

    /// Selects a lower remote dependency version that preserves an installed plugin's reverse constraint.
    @Test
    public void includesInstalledReverseConstraintsDuringSelection(@TempDir Path temporaryDirectory) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/registry", exchange -> respond(exchange, """
                {
                  "schemaVersion": 1,
                  "name": "Reverse Constraint Store",
                  "plugins": [
                    {"id":"dev.test.root","name":"Root","manifestUrl":"%s/root"},
                    {"id":"dev.test.base","name":"Base","manifestUrl":"%s/base"}
                  ]
                }
                """.formatted(baseUrl, baseUrl)));
        server.createContext("/root", exchange -> respond(exchange, repositoryManifest(
                "dev.test.root",
                version(baseUrl, "root", "1.0.0", "4", """
                        [{"id":"dev.test.base","version":">=1.5.0"}]
                        """)
        )));
        server.createContext("/base", exchange -> respond(exchange, repositoryManifest(
                "dev.test.base",
                version(baseUrl, "base", "2.0.0", "5", "[]") + ","
                        + version(baseUrl, "base", "1.5.0", "6", "[]")
        )));
        server.start();

        try {
            PluginStoreManager manager = new PluginStoreManager();
            manager.loadSource(new PluginSource("test", baseUrl + "/registry", null, true, false));
            PluginStoreManifest rootManifest = manager.getPluginManifest("dev.test.root", baseUrl + "/root");
            PluginStoreManifest.PluginVersionEntry rootVersion = rootManifest.getVersion("1.0.0");
            assertNotNull(rootVersion);
            @Unmodifiable Map<String, PluginManifest> installed = Map.of(
                    "dev.test.base",
                    packageManifest("dev.test.base", "1.0.0", "[]"),
                    "dev.test.consumer",
                    packageManifest("dev.test.consumer", "1.0.0", """
                            [{"id":"dev.test.base","version":"<2.0.0"}]
                            """)
            );

            PluginInstallPlan plan = manager.resolveInstallPlan(
                    "dev.test.root",
                    rootVersion,
                    installed,
                    artifactIdentities(installed, "d"),
                    Map.of()
            );

            assertEquals("1.5.0", plan.getEntries().get(0).getVersion());
            assertEquals(PluginInstallPlan.Action.UPDATE, plan.getEntries().get(0).getAction());

            @Unmodifiable Map<String, PluginManifest> legacyReverseDependent = Map.of(
                    "dev.test.base",
                    packageManifest("dev.test.base", "1.0.0", "[]"),
                    "dev.test.consumer",
                    legacyPackageManifest("dev.test.consumer", "1.0.0", """
                            [{"id":"dev.test.base","version":"<2.0.0"}]
                            """)
            );
            PluginInstallPlan legacyPlan = manager.resolveInstallPlan(
                    "dev.test.root",
                    rootVersion,
                    legacyReverseDependent,
                    artifactIdentities(legacyReverseDependent, "e"),
                    Map.of()
            );

            assertEquals("2.0.0", legacyPlan.getEntries().get(0).getVersion());
        } finally {
            server.stop(0);
        }
    }

    /// Creates exact fake artifact identities for one installed-manifest test snapshot.
    ///
    /// @param manifests installed manifests indexed by plugin ID
    /// @param digestCharacter one lower-case hexadecimal character repeated to form each digest
    /// @return immutable exact identities matching every installed manifest
    private static @Unmodifiable Map<String, PluginArtifactIdentity> artifactIdentities(
            @Unmodifiable Map<String, PluginManifest> manifests,
            String digestCharacter
    ) {
        Map<String, PluginArtifactIdentity> identities = new LinkedHashMap<>();
        for (Map.Entry<String, PluginManifest> entry : manifests.entrySet()) {
            identities.put(
                    entry.getKey(),
                    PluginArtifactIdentity.of(entry.getValue(), digestCharacter.repeat(64))
            );
        }
        return Map.copyOf(identities);
    }

    /// Creates one schema-v2 repository manifest around already serialized version entries.
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

    /// Creates one compatible API-v4 repository version with explicit permissions and dependencies.
    ///
    /// @param baseUrl local test server base URL
    /// @param packageName unused package route name
    /// @param pluginVersion published plugin version
    /// @param hashDigit repeated hexadecimal checksum digit
    /// @param dependenciesJson dependency array JSON
    /// @return version entry JSON
    private static String version(
            String baseUrl,
            String packageName,
            String pluginVersion,
            String hashDigit,
            String dependenciesJson
    ) {
        return """
                {
                  "version": "%s",
                  "packageUrl": "%s/%s.npl",
                  "sha256": "%s",
                  "pluginApiVersion": 4,
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "dependencies": %s,
                  "size": 1
                }
                """.formatted(pluginVersion, baseUrl, packageName, hashDigit.repeat(64), dependenciesJson);
    }

    /// Parses one remote API-v4 version with an explicit permission declaration.
    ///
    /// @param pluginId repository plugin ID
    /// @param pluginVersion published version
    /// @param hashDigit repeated hexadecimal checksum digit
    /// @param permissionsJson permission declaration JSON
    /// @return validated remote version metadata
    /// @throws IOException if the generated fixture is invalid
    private static PluginStoreManifest.PluginVersionEntry remoteVersion(
            String pluginId,
            String pluginVersion,
            String hashDigit,
            String permissionsJson
    ) throws IOException {
        PluginStoreManifest manifest = Objects.requireNonNull(
                JsonUtils.GSON.fromJson("""
                        {
                          "schemaVersion": 2,
                          "id": "%s",
                          "versions": [
                            {
                              "version": "%s",
                              "packageUrl": "https://example.com/%s.npl",
                              "sha256": "%s",
                              "pluginApiVersion": 4,
                              "permissions": %s,
                              "requiredPermissions": [],
                              "launcherVersion": "*",
                              "dependencies": [],
                              "size": 1
                            }
                          ]
                        }
                        """.formatted(
                        pluginId,
                        pluginVersion,
                        pluginId,
                        hashDigit.repeat(64),
                        permissionsJson
                ), PluginStoreManifest.class),
                "Generated repository manifest was null"
        );
        manifest.validate(pluginId);
        return Objects.requireNonNull(manifest.getVersion(pluginVersion), "Generated version was missing");
    }

    /// Parses one minimal API-v4 package manifest for installed-graph tests.
    ///
    /// @param pluginId plugin ID
    /// @param pluginVersion installed version
    /// @param dependenciesJson dependency array JSON
    /// @return validated package manifest
    /// @throws IOException if the generated fixture is invalid
    private static PluginManifest packageManifest(
            String pluginId,
            String pluginVersion,
            String dependenciesJson
    ) throws IOException {
        return PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 4,
                  "id": "%s",
                  "name": "%s",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "dev.test.Plugin",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "dependencies": %s
                }
                """.formatted(pluginId, pluginId, pluginVersion, dependenciesJson)));
    }

    /// Parses one minimal installed manifest with an explicit permission declaration.
    ///
    /// @param pluginId plugin ID
    /// @param pluginVersion installed version
    /// @param permissionsJson permission declaration JSON
    /// @return validated package manifest
    /// @throws IOException if the generated fixture is invalid
    private static PluginManifest packageManifestWithPermissions(
            String pluginId,
            String pluginVersion,
            String permissionsJson
    ) throws IOException {
        return PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 4,
                  "id": "%s",
                  "name": "%s",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "dev.test.Plugin",
                  "permissions": %s,
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "dependencies": []
                }
                """.formatted(pluginId, pluginId, pluginVersion, permissionsJson)));
    }

    /// Parses one legacy schema-v3 package manifest retained only for management and update tests.
    ///
    /// @param pluginId plugin ID
    /// @param pluginVersion installed version
    /// @param dependenciesJson dependency array JSON
    /// @return validated legacy package manifest
    /// @throws IOException if the generated fixture is invalid
    private static PluginManifest legacyPackageManifest(
            String pluginId,
            String pluginVersion,
            String dependenciesJson
    ) throws IOException {
        return PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 3,
                  "id": "%s",
                  "name": "%s",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "dev.test.Plugin",
                  "permissions": [],
                  "dependencies": %s
                }
                """.formatted(pluginId, pluginId, pluginVersion, dependenciesJson)));
    }

    /// Writes a UTF-8 JSON response for one local repository route.
    ///
    /// @param exchange incoming HTTP exchange
    /// @param body response body
    /// @throws IOException if the response cannot be written
    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte @Unmodifiable [] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}

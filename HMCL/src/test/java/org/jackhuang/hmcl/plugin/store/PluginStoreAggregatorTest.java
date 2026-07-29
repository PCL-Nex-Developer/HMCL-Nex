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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies bounded, source-priority plugin catalog aggregation against deterministic local registries.
@NotNullByDefault
public final class PluginStoreAggregatorTest {
    /// Bounded wait used by local-server concurrency coordination.
    private static final long TIMEOUT_SECONDS = 5;

    /// Selects the earlier source when both sources publish the same plugin regardless of repository version.
    @Test
    public void sourcePriorityWinsRegardlessOfVersion() throws Exception {
        try (RegistryFixture high = RegistryFixture.start("High", "dev.test.same", "1.0.0");
             RegistryFixture low = RegistryFixture.start("Low", "dev.test.same", "99.0.0");
             PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            PluginSource highSource = source("high", high.registryUrl(), true);
            PluginSource lowSource = source("low", low.registryUrl(), true);

            PluginStoreSnapshot snapshot = aggregator.refresh(
                    List.of(highSource, lowSource)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals("high", snapshot.getWinningItems()
                    .get("dev.test.same").getSource().getId());
            assertEquals(List.of("low"), snapshot.getConflictCandidates()
                    .get("dev.test.same").stream()
                    .map(item -> item.getSource().getId()).toList());
        }
    }

    /// Combines entries with distinct IDs from independent enabled sources.
    @Test
    public void aggregatesUniqueItemsFromEachEnabledSource() throws Exception {
        try (RegistryFixture first = RegistryFixture.start("First", "dev.test.first", "1.0.0");
             RegistryFixture second = RegistryFixture.start("Second", "dev.test.second", "1.0.0");
             PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            PluginStoreSnapshot snapshot = aggregator.refresh(List.of(
                    source("first", first.registryUrl(), true),
                    source("second", second.registryUrl(), true)
            )).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals(List.of("dev.test.first", "dev.test.second"),
                    snapshot.getWinningItems().keySet().stream().toList());
            assertEquals(2, snapshot.getSourceResults().size());
            assertTrue(snapshot.getConflictCandidates().isEmpty());
        }
    }

    /// Recomputes the winner using the supplied source priority rather than a previous refresh result.
    @Test
    public void reorderedSourcesSelectTheNewFirstWinner() throws Exception {
        try (RegistryFixture first = RegistryFixture.start("First", "dev.test.same", "1.0.0");
             RegistryFixture second = RegistryFixture.start("Second", "dev.test.same", "2.0.0");
             PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            PluginStoreSnapshot firstSnapshot = aggregator.refresh(List.of(
                    source("first", first.registryUrl(), true),
                    source("second", second.registryUrl(), true)
            )).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            PluginStoreSnapshot reorderedSnapshot = aggregator.refresh(List.of(
                    source("second", second.registryUrl(), true),
                    source("first", first.registryUrl(), true)
            )).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals("first", firstSnapshot.getWinningItems().get("dev.test.same").getSource().getId());
            assertEquals("second", reorderedSnapshot.getWinningItems().get("dev.test.same").getSource().getId());
        }
    }

    /// Uses a lower-priority source when an earlier source cannot load its registry.
    @Test
    public void registryFailureDegradesToTheLowerPriorityWinner() throws Exception {
        try (RegistryFixture failed = RegistryFixture.startRegistryFailure();
             RegistryFixture lower = RegistryFixture.start("Lower", "dev.test.same", "1.0.0");
             PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            PluginStoreSnapshot snapshot = aggregator.refresh(List.of(
                    source("high", failed.registryUrl(), true),
                    source("low", lower.registryUrl(), true)
            )).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals("low", snapshot.getWinningItems().get("dev.test.same").getSource().getId());
            assertEquals(PluginSourceLoadResult.Status.FAILED, snapshot.getSourceResults().get(0).getStatus());
            assertEquals(1, snapshot.getFailures().size());
        }
    }

    /// Retains the source-level failures when no registry can produce a catalog.
    @Test
    public void reportsEveryFailureWhenAllSourcesFail() throws Exception {
        try (RegistryFixture first = RegistryFixture.startRegistryFailure();
             RegistryFixture second = RegistryFixture.startRegistryFailure();
             PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            PluginStoreSnapshot snapshot = aggregator.refresh(List.of(
                    source("first", first.registryUrl(), true),
                    source("second", second.registryUrl(), true)
            )).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertTrue(snapshot.getWinningItems().isEmpty());
            assertEquals(2, snapshot.getFailures().size());
            assertEquals(List.of(
                    PluginSourceLoadResult.Status.FAILED,
                    PluginSourceLoadResult.Status.FAILED
            ), snapshot.getSourceResults().stream().map(PluginSourceLoadResult::getStatus).toList());
        }
    }

    /// Retains the registry item while identifying an individual manifest load as a partial source failure.
    @Test
    public void recordsManifestFailureWithoutDiscardingTheRegistryItem() throws Exception {
        try (RegistryFixture fixture = RegistryFixture.startManifestFailure("Partial", "dev.test.partial");
             PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            PluginStoreSnapshot snapshot = aggregator.refresh(List.of(
                    source("partial", fixture.registryUrl(), true)
            )).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            PluginSourceLoadResult result = snapshot.getSourceResults().get(0);

            assertEquals(PluginSourceLoadResult.Status.PARTIAL_FAILURE, result.getStatus());
            assertEquals(1, result.getPartialManifestFailureCount());
            assertEquals(1, result.getItems().size());
            assertEquals(null, result.getItems().get(0).getManifest());
            assertEquals("partial", snapshot.getWinningItems().get("dev.test.partial").getSource().getId());
        }
    }

    /// Removes non-HTTP URL credentials, queries, and fragments from source failure diagnostics while retaining the original failure.
    @Test
    public void sourceFailureMessageSanitizesNonHttpUrlCredentialsAndParameters() {
        IOException failure = new IOException(
                "Unable to load ftp://user:secret@example.test/plugins.json?token=private#fragment"
        );
        PluginSourceLoadResult result = PluginSourceLoadResult.failed(
                source("failed", "https://example.test/plugins.json", true),
                0,
                failure
        );

        assertEquals("Unable to load ftp://example.test/plugins.json", result.getFailureMessage());
        assertSame(failure, result.getFailure());
        assertTrue(Objects.requireNonNull(result.getFailureMessage()).contains("example.test"));
        assertTrue(!result.getFailureMessage().contains("secret"));
        assertTrue(!result.getFailureMessage().contains("token=private"));
        assertTrue(!result.getFailureMessage().contains("fragment"));
    }

    /// Rejects success-result counts that do not match the source items with unresolved manifests.
    @Test
    public void sourceResultSuccessRejectsMismatchedPartialManifestCounts() throws Exception {
        try (RegistryFixture fixture = RegistryFixture.start("Result", "dev.test.result", "1.0.0")) {
            PluginSource source = source("result", fixture.registryUrl(), true);
            PluginStoreManager manager = new PluginStoreManager();
            manager.loadSource(source);
            PluginStoreRegistry registry = Objects.requireNonNull(manager.getRegistry());
            PluginStoreItem resolved = manager.getStoreItems().get(0);
            PluginStoreItem unresolved = new PluginStoreItem(
                    source,
                    registry,
                    manager,
                    resolved.getEntry(),
                    null
            );

            PluginSourceLoadResult success = PluginSourceLoadResult.success(
                    source, 0, List.of(resolved), 0, registry, manager
            );
            PluginSourceLoadResult partial = PluginSourceLoadResult.success(
                    source, 0, List.of(unresolved), 1, registry, manager
            );

            assertEquals(PluginSourceLoadResult.Status.SUCCESS, success.getStatus());
            assertEquals(PluginSourceLoadResult.Status.PARTIAL_FAILURE, partial.getStatus());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PluginSourceLoadResult.success(source, 0, List.of(resolved), 1, registry, manager)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PluginSourceLoadResult.success(source, 0, List.of(unresolved), 0, registry, manager)
            );
        }
    }

    /// Does not submit disabled source requests to the remote registry.
    @Test
    public void disabledSourcesMakeNoRequests() throws Exception {
        try (RegistryFixture fixture = RegistryFixture.start("Disabled", "dev.test.disabled", "1.0.0");
             PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            PluginStoreSnapshot snapshot = aggregator.refresh(List.of(
                    source("disabled", fixture.registryUrl(), false)
            )).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals(0, fixture.getRegistryRequests());
            assertEquals(PluginSourceLoadResult.Status.DISABLED, snapshot.getSourceResults().get(0).getStatus());
            assertTrue(snapshot.getWinningItems().isEmpty());
        }
    }

    /// Keeps an older request result available to its caller without allowing it to replace a newer published snapshot.
    @Test
    public void staleGenerationCannotReplaceTheCurrentSnapshot() throws Exception {
        CountDownLatch firstRegistryStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRegistry = new CountDownLatch(1);
        try (RegistryFixture old = RegistryFixture.startBlocking(
                "Old", firstRegistryStarted, releaseFirstRegistry, new AtomicInteger(), new AtomicInteger()
        );
             RegistryFixture replacement = RegistryFixture.start("Replacement", "dev.test.replacement", "1.0.0");
             PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            var staleFuture = aggregator.refresh(List.of(source("old", old.registryUrl(), true)));
            assertTrue(firstRegistryStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            PluginStoreSnapshot current = aggregator.refresh(List.of(
                    source("replacement", replacement.registryUrl(), true)
            )).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            releaseFirstRegistry.countDown();
            PluginStoreSnapshot stale = staleFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals("old", stale.getSourceResults().get(0).getSource().getId());
            assertEquals("replacement", current.getWinningItems().get("dev.test.replacement").getSource().getId());
            assertSame(current, aggregator.getCurrentSnapshot());
            assertEquals(current.getGeneration(), aggregator.getCurrentSnapshot().getGeneration());
        } finally {
            releaseFirstRegistry.countDown();
        }
    }

    /// Limits active registry requests to the configured executor concurrency.
    @Test
    public void limitsConcurrentSourceRequests() throws Exception {
        int concurrency = 4;
        AtomicInteger activeRequests = new AtomicInteger();
        AtomicInteger maximumActiveRequests = new AtomicInteger();
        CountDownLatch requestsStarted = new CountDownLatch(concurrency);
        CountDownLatch releaseRequests = new CountDownLatch(1);
        try (RegistryFixture first = RegistryFixture.startBlocking(
                "First", requestsStarted, releaseRequests, activeRequests, maximumActiveRequests
        );
             RegistryFixture second = RegistryFixture.startBlocking(
                     "Second", requestsStarted, releaseRequests, activeRequests, maximumActiveRequests
             );
             RegistryFixture third = RegistryFixture.startBlocking(
                     "Third", requestsStarted, releaseRequests, activeRequests, maximumActiveRequests
             );
             RegistryFixture fourth = RegistryFixture.startBlocking(
                     "Fourth", requestsStarted, releaseRequests, activeRequests, maximumActiveRequests
             );
             RegistryFixture fifth = RegistryFixture.startBlocking(
                     "Fifth", requestsStarted, releaseRequests, activeRequests, maximumActiveRequests
             );
             RegistryFixture sixth = RegistryFixture.startBlocking(
                     "Sixth", requestsStarted, releaseRequests, activeRequests, maximumActiveRequests
             );
             PluginStoreAggregator aggregator = new PluginStoreAggregator(concurrency)) {
            var snapshotFuture = aggregator.refresh(List.of(
                    source("first", first.registryUrl(), true),
                    source("second", second.registryUrl(), true),
                    source("third", third.registryUrl(), true),
                    source("fourth", fourth.registryUrl(), true),
                    source("fifth", fifth.registryUrl(), true),
                    source("sixth", sixth.registryUrl(), true)
            ));
            assertTrue(requestsStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(concurrency, activeRequests.get());
            assertTrue(maximumActiveRequests.get() <= concurrency);

            releaseRequests.countDown();
            snapshotFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(maximumActiveRequests.get() <= concurrency);
        } finally {
            releaseRequests.countDown();
        }
    }

    /// Creates one source configuration for a local fixture.
    ///
    /// @param id source identifier
    /// @param registryUrl registry endpoint
    /// @param enabled whether the aggregator may request the source
    /// @return source configuration
    private static PluginSource source(String id, String registryUrl, boolean enabled) {
        return new PluginSource(id, registryUrl, null, enabled, false);
    }

    /// Sends one successful local HTTP response.
    ///
    /// @param exchange local HTTP exchange
    /// @param body response bytes
    /// @throws IOException if the response cannot be written
    private static void respond(HttpExchange exchange, byte @Unmodifiable [] body) throws IOException {
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    /// Updates an atomic maximum with a newly observed request count.
    ///
    /// @param maximum destination maximum
    /// @param candidate observed active request count
    private static void updateMaximum(AtomicInteger maximum, int candidate) {
        maximum.accumulateAndGet(candidate, Math::max);
    }

    /// Owns a local registry and optional manifest endpoint for one aggregator test source.
    @NotNullByDefault
    private static final class RegistryFixture implements AutoCloseable {
        /// Local HTTP server serving the registry and manifest responses.
        private final HttpServer server;

        /// Number of requests received by the registry endpoint.
        private final AtomicInteger registryRequests;

        /// Creates one started local registry fixture.
        ///
        /// @param server local HTTP server
        /// @param registryRequests registry request counter
        private RegistryFixture(HttpServer server, AtomicInteger registryRequests) {
            this.server = server;
            this.registryRequests = registryRequests;
        }

        /// Starts a registry that contains one entry and a resolvable manifest.
        ///
        /// @param registryName source-visible registry name
        /// @param pluginId registry entry ID
        /// @param version manifest version
        /// @return started registry fixture
        /// @throws IOException if the local server cannot be created
        private static RegistryFixture start(String registryName, String pluginId, String version) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicInteger registryRequests = new AtomicInteger();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/plugins.json", exchange -> {
                registryRequests.incrementAndGet();
                respond(exchange, registryWithEntry(registryName, pluginId, baseUrl + "/manifest.json"));
            });
            server.createContext("/manifest.json", exchange -> respond(exchange, manifest(pluginId, version)));
            server.start();
            return new RegistryFixture(server, registryRequests);
        }

        /// Starts a registry that always rejects catalog requests.
        ///
        /// @return started failing registry fixture
        /// @throws IOException if the local server cannot be created
        private static RegistryFixture startRegistryFailure() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicInteger registryRequests = new AtomicInteger();
            server.createContext("/plugins.json", exchange -> {
                registryRequests.incrementAndGet();
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_INTERNAL_ERROR, -1);
                exchange.close();
            });
            server.start();
            return new RegistryFixture(server, registryRequests);
        }

        /// Starts a registry whose sole repository manifest request fails.
        ///
        /// @param registryName source-visible registry name
        /// @param pluginId registry entry ID
        /// @return started partial-failure fixture
        /// @throws IOException if the local server cannot be created
        private static RegistryFixture startManifestFailure(String registryName, String pluginId) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicInteger registryRequests = new AtomicInteger();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/plugins.json", exchange -> {
                registryRequests.incrementAndGet();
                respond(exchange, registryWithEntry(registryName, pluginId, baseUrl + "/manifest.json"));
            });
            server.createContext("/manifest.json", exchange -> {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_INTERNAL_ERROR, -1);
                exchange.close();
            });
            server.start();
            return new RegistryFixture(server, registryRequests);
        }

        /// Starts a registry request that waits until the test releases its shared request gate.
        ///
        /// @param registryName source-visible registry name
        /// @param started signal counted down after the request becomes active
        /// @param release gate controlling response completion
        /// @param activeRequests active registry request count
        /// @param maximumActiveRequests observed maximum active registry request count
        /// @return started blocking registry fixture
        /// @throws IOException if the local server cannot be created
        private static RegistryFixture startBlocking(
                String registryName,
                CountDownLatch started,
                CountDownLatch release,
                AtomicInteger activeRequests,
                AtomicInteger maximumActiveRequests
        ) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicInteger registryRequests = new AtomicInteger();
            server.createContext("/plugins.json", exchange -> {
                registryRequests.incrementAndGet();
                int active = activeRequests.incrementAndGet();
                updateMaximum(maximumActiveRequests, active);
                started.countDown();
                try {
                    if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release registry request");
                    }
                    respond(exchange, emptyRegistry(registryName));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting to release registry request", exception);
                } finally {
                    activeRequests.decrementAndGet();
                }
            });
            server.start();
            return new RegistryFixture(server, registryRequests);
        }

        /// Returns this fixture's registry endpoint.
        ///
        /// @return loopback registry URL
        private String registryUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/plugins.json";
        }

        /// Returns the observed registry request count.
        ///
        /// @return registry request count
        private int getRegistryRequests() {
            return registryRequests.get();
        }

        /// Stops the fixture server after its test completes.
        @Override
        public void close() {
            server.stop(0);
        }

        /// Serializes a registry containing one repository entry.
        ///
        /// @param name registry display name
        /// @param pluginId registry entry ID
        /// @param manifestUrl repository manifest URL
        /// @return registry JSON bytes
        private static byte @Unmodifiable [] registryWithEntry(String name, String pluginId, String manifestUrl) {
            return """
                    {
                      "schemaVersion": 1,
                      "name": "%s",
                      "plugins": [
                        {
                          "id": "%s",
                          "name": "Aggregator Plugin",
                          "manifestUrl": "%s"
                        }
                      ]
                    }
                    """.formatted(name, pluginId, manifestUrl).getBytes(StandardCharsets.UTF_8);
        }

        /// Serializes an empty registry for request-concurrency tests.
        ///
        /// @param name registry display name
        /// @return registry JSON bytes
        private static byte @Unmodifiable [] emptyRegistry(String name) {
            return """
                    {
                      "schemaVersion": 1,
                      "name": "%s",
                      "plugins": []
                    }
                    """.formatted(name).getBytes(StandardCharsets.UTF_8);
        }

        /// Serializes a valid source-bound repository manifest.
        ///
        /// @param pluginId registry entry ID
        /// @param version repository version
        /// @return manifest JSON bytes
        private static byte @Unmodifiable [] manifest(String pluginId, String version) {
            return """
                    {
                      "schemaVersion": 2,
                      "id": "%s",
                      "versions": [
                        {
                          "version": "%s",
                          "packageUrl": "https://example.com/plugin.npl",
                          "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                          "pluginApiVersion": 4,
                          "permissions": [],
                          "requiredPermissions": [],
                          "launcherVersion": "*",
                          "dependencies": [],
                          "size": 1
                        }
                      ]
                    }
                    """.formatted(pluginId, version).getBytes(StandardCharsets.UTF_8);
        }
    }
}

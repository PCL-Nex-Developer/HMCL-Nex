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
package org.jackhuang.hmcl.ui.main;

import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.PluginRuntimeStatus;
import org.jackhuang.hmcl.plugin.store.PluginInstallPlan;
import org.jackhuang.hmcl.plugin.store.PluginSource;
import org.jackhuang.hmcl.plugin.store.PluginSourceLoadResult;
import org.jackhuang.hmcl.plugin.store.PluginStoreDependencyResolver;
import org.jackhuang.hmcl.plugin.store.PluginStoreItem;
import org.jackhuang.hmcl.plugin.store.PluginStoreManager;
import org.jackhuang.hmcl.plugin.store.PluginStoreManifest;
import org.jackhuang.hmcl.plugin.store.PluginStoreRegistry;
import org.jackhuang.hmcl.plugin.store.PluginStoreSnapshot;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies README sanitization and mandatory fail-closed permission review in the plugin store UI model.
@NotNullByDefault
public final class PluginStorePageTest {
    /// Removes every resource-loading element while retaining text and explicit hyperlinks.
    @Test
    public void stripAutomaticReadmeResources() {
        Document document = PluginStorePage.parseSafeReadmeHtml("""
                <h1>Plugin</h1>
                <img src="http://127.0.0.1:8123/probe" alt="probe">
                <picture><source srcset="https://example.com/image.png"></picture>
                <iframe src="https://example.com/frame"></iframe>
                <script src="https://example.com/script.js"></script>
                <a href="docs/usage.html">Usage</a>
                """, "https://example.com/repository/README.html");

        assertTrue(document.select(
                "img, picture, source, video, audio, iframe, object, embed, link, script, style"
        ).isEmpty());
        assertTrue(document.text().contains("Plugin"));
        assertEquals(
                "https://example.com/repository/docs/usage.html",
                document.selectFirst("a").absUrl("href")
        );
    }

    /// Accepts public HTTPS and development loopback HTTP while rejecting unsafe or remote cleartext links.
    @Test
    public void externalLinksRequireHttpsOrLoopbackHttp() {
        assertTrue(PluginStorePage.isSafeExternalLink("https://example.com/plugin"));
        assertTrue(PluginStorePage.isSafeExternalLink("http://localhost:8080/plugin"));
        assertTrue(PluginStorePage.isSafeExternalLink("http://127.0.0.1:8080/plugin"));
        assertFalse(PluginStorePage.isSafeExternalLink("http://127.0.0.1.attacker.example/plugin"));
        assertFalse(PluginStorePage.isSafeExternalLink("http://example.com/plugin"));
        assertFalse(PluginStorePage.isSafeExternalLink("file:///tmp/plugin"));
        assertFalse(PluginStorePage.isSafeExternalLink("javascript:alert(1)"));
        assertFalse(PluginStorePage.isSafeExternalLink("data:text/plain,plugin"));
    }

    /// Normalizes registry prose and bounds descriptions so one row cannot dominate the store list.
    @Test
    public void storeDescriptionSummaryIsBounded() {
        String summary = PluginStorePage.summarizeDescription("A\n\nB " + "x".repeat(220));
        assertTrue(summary.startsWith("A B "));
        assertTrue(summary.length() <= 71);
        assertTrue(summary.endsWith("\u2026"));

        String wideSummary = PluginStorePage.summarizeDescription("插件".repeat(100));
        assertTrue(wideSummary.codePointCount(0, wideSummary.length()) <= 36);
        assertTrue(wideSummary.endsWith("\u2026"));
    }

    /// Bounds long registry names so minimum-width rows retain their status and action controls.
    @Test
    public void storeTitleSummaryIsBounded() {
        String narrow = PluginStorePage.summarizeTitle("Plugin ".repeat(30));
        assertTrue(narrow.endsWith("\u2026"));
        assertTrue(narrow.length() <= 35);

        String wide = PluginStorePage.summarizeTitle("插件".repeat(40));
        assertTrue(wide.endsWith("\u2026"));
        assertTrue(wide.codePointCount(0, wide.length()) <= 18);
    }

    /// Separates descriptive prose from metadata while avoiding blank lines for missing sections.
    @Test
    public void storeRowSubtitleUsesReadableSections() {
        assertEquals(
                "Description\n\nAuthor\nCategory\nVersion",
                PluginStorePage.composePluginRowSubtitle(
                        "Description",
                        List.of("Author", "Category", "Version")
                )
        );
        assertEquals(
                "Author\nVersion",
                PluginStorePage.composePluginRowSubtitle("", List.of("Author", "Version"))
        );
        assertEquals(
                "Description",
                PluginStorePage.composePluginRowSubtitle("Description", List.of())
        );
    }

    /// Uses the same future installed-state definition as dependency planning for root install actions.
    @Test
    public void rootInstallActionUsesFuturePlanningState() {
        PluginManifest installed = new PluginManifest(
                "dev.hmclnex.test.installed",
                "Installed",
                "1.0.0",
                PluginManifest.PluginType.JAVA,
                "dev.hmclnex.test.Plugin"
        );

        assertEquals(PluginInstallPlan.Action.INSTALL, PluginStorePage.rootInstallationAction(null));
        assertEquals(PluginInstallPlan.Action.UPDATE, PluginStorePage.rootInstallationAction(installed));
    }

    /// Preserves complete HMCL Nex version constraints instead of presenting them as minimum versions.
    @Test
    public void launcherVersionRequirementKeepsFullConstraint() {
        assertEquals(
                ">=26.8-beta.3-fix <27.0",
                PluginStorePage.launcherVersionRequirementText(">=26.8-beta.3-fix <27.0")
        );
        assertFalse(PluginStorePage.launcherVersionRequirementText("*").isBlank());
    }

    /// Maps incompatible versions to the launcher error symbol instead of a success checkmark.
    @Test
    public void compatibilityIconMatchesRuntimeState() {
        assertEquals(SVG.CHECK_CIRCLE, PluginStorePage.compatibilityIcon(true));
        assertEquals(SVG.ERROR, PluginStorePage.compatibilityIcon(false));
    }

    /// Selects every required permission while carrying forward only optional grants retained by an update.
    @Test
    public void permissionPreselectionSeparatesRequiredAndOptionalCapabilities() {
        assertEquals(
                Set.of(PluginPermission.MIXIN, PluginPermission.NETWORK),
                PluginStorePage.initialPermissionsFor(
                        PluginInstallPlan.Action.UPDATE,
                        List.of(PluginPermission.MIXIN),
                        List.of(PluginPermission.NETWORK, PluginPermission.CLIPBOARD),
                        Set.of(PluginPermission.NETWORK, PluginPermission.PROCESS)
                )
        );
        assertEquals(
                Set.of(PluginPermission.FILESYSTEM),
                PluginStorePage.initialPermissionsFor(
                        PluginInstallPlan.Action.INSTALL,
                        List.of(PluginPermission.FILESYSTEM),
                        List.of(PluginPermission.NETWORK),
                        Set.of(PluginPermission.NETWORK)
                )
        );
    }

    /// Creates a new editable review request for updates whose permission declaration is unchanged or empty.
    @Test
    public void everyUpdateCreatesPermissionReviewRequest() {
        PluginPermissionRequest unchangedRequest = PluginStorePage.createPermissionReviewRequest(
                "dev.hmclnex.test.unchanged-update",
                "Unchanged Update",
                "1.0.0",
                PluginInstallPlan.Action.UPDATE,
                List.of(),
                List.of(PluginPermission.NETWORK),
                Set.of(PluginPermission.NETWORK),
                List.of(),
                List.of(PluginPermission.NETWORK)
        );
        PluginPermissionRequest emptyRequest = PluginStorePage.createPermissionReviewRequest(
                "dev.hmclnex.test.empty-update",
                "Empty Update",
                "1.0.0",
                PluginInstallPlan.Action.UPDATE,
                List.of(),
                List.of(),
                Set.of(PluginPermission.NETWORK),
                List.of(),
                List.of(PluginPermission.NETWORK)
        );
        PluginPermissionRequest expandedRequest = PluginStorePage.createPermissionReviewRequest(
                "dev.hmclnex.test.expanded-update",
                "Expanded Update",
                "2.0.0",
                PluginInstallPlan.Action.UPDATE,
                List.of(PluginPermission.NETWORK),
                List.of(PluginPermission.CLIPBOARD),
                Set.of(PluginPermission.NETWORK),
                List.of(),
                List.of(PluginPermission.NETWORK)
        );

        assertTrue(unchangedRequest.isEditable());
        assertTrue(unchangedRequest.isUpdate());
        assertEquals(List.of(PluginPermission.NETWORK), unchangedRequest.getDeclaredPermissions());
        assertEquals(Set.of(PluginPermission.NETWORK), unchangedRequest.getInitiallyGrantedPermissions());
        assertTrue(unchangedRequest.getNewlyRequiredPermissions().isEmpty());
        assertTrue(unchangedRequest.getNewlyOptionalPermissions().isEmpty());
        assertTrue(emptyRequest.isEditable());
        assertTrue(emptyRequest.isUpdate());
        assertTrue(emptyRequest.getDeclaredPermissions().isEmpty());
        assertTrue(emptyRequest.getInitiallyGrantedPermissions().isEmpty());
        assertEquals(Set.of(PluginPermission.NETWORK), expandedRequest.getNewlyRequiredPermissions());
        assertEquals(Set.of(PluginPermission.CLIPBOARD), expandedRequest.getNewlyOptionalPermissions());
        assertEquals(Set.of(PluginPermission.NETWORK), expandedRequest.getInitiallyGrantedPermissions());
    }

    /// Rejects defensive attempts to create a permission review request for an unchanged reused artifact.
    @Test
    public void reusedArtifactCannotCreatePermissionReviewRequest() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PluginStorePage.createPermissionReviewRequest(
                        "dev.hmclnex.test.reuse",
                        "Reuse",
                        "1.0.0",
                        PluginInstallPlan.Action.REUSE,
                        List.of(),
                        List.of(PluginPermission.NETWORK),
                        Set.of(PluginPermission.NETWORK),
                        List.of(),
                        List.of(PluginPermission.NETWORK)
                )
        );
    }

    /// Removes undeclared capabilities before an installation form can expose a selected grant.
    @Test
    public void permissionRequestCannotPregrantUndeclaredCapability() {
        PluginPermissionRequest request = new PluginPermissionRequest(
                "dev.hmclnex.test.permissions",
                "Permissions",
                "1.0.0",
                List.of(PluginPermission.FILESYSTEM),
                List.of(PluginPermission.NETWORK),
                Set.of(PluginPermission.NETWORK, PluginPermission.PROCESS),
                true,
                false,
                List.of(),
                List.of()
        );

        assertEquals(
                Set.of(PluginPermission.FILESYSTEM, PluginPermission.NETWORK),
                request.getInitiallyGrantedPermissions()
        );
        assertEquals(
                List.of(PluginPermission.FILESYSTEM, PluginPermission.NETWORK),
                request.getDeclaredPermissions()
        );
    }

    /// Rejects ambiguous declarations that classify one capability as both required and optional.
    @Test
    public void permissionRequestRejectsOverlappingClassifications() {
        assertThrows(IllegalArgumentException.class, () -> new PluginPermissionRequest(
                "dev.hmclnex.test.overlap",
                "Overlap",
                "1.0.0",
                List.of(PluginPermission.NETWORK),
                List.of(PluginPermission.NETWORK),
                Set.of(),
                true,
                false,
                List.of(),
                List.of()
        ));
    }

    /// Always includes required permissions and discards selections outside the optional declaration.
    @Test
    public void permissionPaneGrantMergeIsRequiredAndDeclarationLimited() {
        assertEquals(
                Set.of(PluginPermission.FILESYSTEM, PluginPermission.NETWORK),
                PluginPermissionPane.mergeGrantedPermissions(
                        List.of(PluginPermission.FILESYSTEM),
                        List.of(PluginPermission.NETWORK),
                        Set.of(PluginPermission.NETWORK, PluginPermission.PROCESS)
                )
        );
    }

    /// Requires every supported permission, including high-risk Mixin access, to have user-facing text.
    @Test
    public void everyPermissionHasLocalizedNameAndDescription() {
        for (PluginPermission permission : PluginPermission.values()) {
            assertTrue(
                    org.jackhuang.hmcl.util.i18n.I18n.hasKey("plugin.permission." + permission.getId()),
                    () -> "Missing permission name for " + permission.getId()
            );
            assertTrue(
                    org.jackhuang.hmcl.util.i18n.I18n.hasKey(
                            "plugin.permission." + permission.getId() + ".description"
                    ),
                    () -> "Missing permission description for " + permission.getId()
            );
        }
        for (String key : List.of(
                "plugin.permission.required",
                "plugin.permission.optional",
                "plugin.permissions.required",
                "plugin.permissions.optional",
                "plugin.permissions.optional_denied_warning",
                "plugin.install.permissions.new_required",
                "plugin.install.permissions.new_optional",
                "plugin.store.launcher_version",
                "plugin.store.install.incompatible"
        )) {
            assertTrue(
                    org.jackhuang.hmcl.util.i18n.I18n.hasKey(key),
                    () -> "Missing plugin UI translation for " + key
            );
        }
    }

    /// Summarizes the persisted enabled-source count without selecting an active registry URL.
    @Test
    public void sourceSummaryReportsEnabledSourceCount() {
        assertEquals(
                "1 of 2 plugin sources enabled",
                PluginStorePage.sourceSummary(List.of(
                        new PluginSource(PluginSource.OFFICIAL_ID, PluginStoreManager.DEFAULT_REGISTRY_URL, null, true, true),
                        new PluginSource("source_one", "https://plugins.example.org/plugins.json", null, false, false)
                ))
        );
    }

    /// Publishes the current snapshot before source-management refresh callbacks read it.
    @Test
    public void snapshotPublicationPrecedesSourceManagementRefresh() {
        java.util.concurrent.atomic.AtomicReference<PluginStoreSnapshot> current = new java.util.concurrent.atomic.AtomicReference<>();
        PluginStoreSnapshot snapshot = degradedSnapshot();
        java.util.concurrent.atomic.AtomicLong observedGeneration = new java.util.concurrent.atomic.AtomicLong(-1);

        PluginStorePage.publishSnapshotThenNotify(
                current::set,
                snapshot,
                published -> observedGeneration.set(current.get().getGeneration())
        );

        assertEquals(1, observedGeneration.get());
    }

    /// Presents successful catalog rows while visibly warning that one enabled source failed.
    @Test
    public void partialSourceFailureProducesVisibleDegradedState() {
        PluginStorePage.StorePresentation presentation = PluginStorePage.presentationFor(degradedSnapshot());

        assertEquals(PluginStorePage.StoreState.DEGRADED, presentation.state());
        assertTrue(presentation.showPluginRows());
        assertEquals(1, presentation.failedSourceCount());
    }

    /// Identifies the winner's source for every remotely downloaded install-plan row.
    @Test
    public void everyDownloadedPlanRowIdentifiesItsWinningSource() throws IOException {
        List<String> rows = PluginStorePage.formatInstallPlan(planWithTwoRemoteSources());

        assertTrue(rows.stream().anyMatch(row -> row.contains("Source A")));
        assertTrue(rows.stream().anyMatch(row -> row.contains("Source B")));
    }

    /// Retains the accepted aggregate state while a newer refresh is still active.
    @Test
    public void refreshingPresentationRetainsPriorAcceptedPluginRows() {
        assertTrue(PluginStorePage.presentationFor(degradedSnapshot(), true).showPluginRows());
    }

    /// Rejects a superseded completion even when its aggregate result was once current.
    @Test
    public void staleCompletionCannotReplaceNewerAcceptedSnapshot() {
        PluginStoreSnapshot stale = degradedSnapshot();
        PluginStoreSnapshot current = conflictingSnapshot();

        assertFalse(PluginStorePage.canPublishSnapshot(1, 2, stale, stale));
        assertFalse(PluginStorePage.canPublishSnapshot(2, 2, stale, current));
        assertTrue(PluginStorePage.canPublishSnapshot(2, 2, current, current));
    }

    /// Closes a page-owned aggregate loader exactly once at its deterministic application lifetime boundary.
    @Test
    public void aggregateLoaderClosesOnceAtApplicationShutdown() {
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger closeCount = new AtomicInteger();

        PluginStorePage.closeAggregatorWhenApplicationStops(false, closed, closeCount::incrementAndGet);
        PluginStorePage.closeAggregatorWhenApplicationStops(false, closed, closeCount::incrementAndGet);
        assertEquals(0, closeCount.get());

        PluginStorePage.closeAggregatorWhenApplicationStops(true, closed, closeCount::incrementAndGet);
        PluginStorePage.closeAggregatorWhenApplicationStops(true, closed, closeCount::incrementAndGet);

        assertEquals(1, closeCount.get());
    }

    /// Retains the degradation warning for the snapshot that resolved a plan when a later refresh is healthy.
    @Test
    public void planWarningUsesResolvedSnapshotAfterLaterRefresh() {
        PluginStoreSnapshot resolvedSnapshot = degradedSnapshot();
        PluginStoreSnapshot laterSnapshot = conflictingSnapshot();

        assertTrue(Objects.requireNonNull(PluginStorePage.degradedCatalogWarning(resolvedSnapshot)).contains("Source B"));
        assertNull(PluginStorePage.degradedCatalogWarning(laterSnapshot));
    }

    /// Redacts hostile source aliases and IDs before displaying degraded catalog warnings.
    @Test
    public void degradedCatalogWarningNeverContainsSourceUrls() {
        PluginSource hostile = new PluginSource(
                "https://id.example.test/catalog?token=secret#fragment",
                "https://source.example.test/plugins.json",
                "https://user:password@alias.example.test/catalog?token=secret#fragment",
                true,
                false
        );
        PluginStoreSnapshot snapshot = new PluginStoreSnapshot(1, List.of(
                PluginSourceLoadResult.failed(hostile, 1, new IOException("offline"))
        ));

        String warning = PluginStorePage.degradedCatalogWarning(snapshot);
        assertFalse(warning.contains("://"));
        assertFalse(warning.contains("password"));
        assertFalse(warning.contains("token"));
    }

    /// Returns no plugin rows when every configured enabled source fails.
    @Test
    public void allFailedSourcesProduceAnAllFailedPresentation() {
        PluginSource source = new PluginSource(
                "source_a", "https://source-a.example.test/plugins.json", "Source A", true, false);
        PluginStorePage.StorePresentation presentation = PluginStorePage.presentationFor(new PluginStoreSnapshot(1, List.of(
                PluginSourceLoadResult.failed(source, 1, new IOException("offline"))
        )));

        assertEquals(PluginStorePage.StoreState.ALL_FAILED, presentation.state());
        assertFalse(presentation.showPluginRows());
    }

    /// Returns the source-management state when no configured source is enabled.
    @Test
    public void disabledSourcesProduceNoEnabledSourcesPresentation() {
        PluginSource source = new PluginSource(
                "source_a", "https://source-a.example.test/plugins.json", "Source A", false, false);
        PluginStorePage.StorePresentation presentation = PluginStorePage.presentationFor(new PluginStoreSnapshot(1, List.of(
                PluginSourceLoadResult.disabled(source)
        )));

        assertEquals(PluginStorePage.StoreState.NO_ENABLED_SOURCES, presentation.state());
        assertFalse(presentation.showPluginRows());
    }

    /// Marks aggregate winners with source conflicts for a dedicated warning and details presentation.
    @Test
    public void conflictingCandidatesProduceConflictPresentation() {
        PluginStorePage.StorePresentation presentation = PluginStorePage.presentationFor(conflictingSnapshot());

        assertEquals(PluginStorePage.StoreState.CONFLICTS, presentation.state());
        assertTrue(presentation.hasConflicts());
    }

    /// Adds the winning source to aggregate row metadata rather than selecting a global registry name.
    @Test
    public void rowSubtitleContainsWinningSourceBadge() {
        PluginStoreItem item = successfulItem(
                new PluginSource("source_a", "https://source-a.example.test/plugins.json", "Source A", true, false),
                "dev.hmclnex.source.badge",
                "Badge Plugin"
        );

        assertTrue(PluginStorePage.buildPluginRowSubtitle(item, null).contains("Source: Source A"));
    }

    /// Keeps favorites attached to plugin identity regardless of the source that currently wins that identity.
    @Test
    public void favoriteFilteringKeysOnlyByPluginId() {
        assertTrue(PluginStorePage.matchesFavorite(Set.of("dev.hmclnex.favorite"),
                successfulItem(
                        new PluginSource("source_b", "https://source-b.example.test/plugins.json", "Source B", true, false),
                        "dev.hmclnex.favorite",
                        "Favorite"
                )));
    }

    /// Retains installed state after a source deletion removes the catalog winner.
    @Test
    public void installedStateSurvivesSourceRemoval() {
        PluginManifest installed = new PluginManifest(
                "dev.hmclnex.removed", "Removed Source Plugin", "1.0.0",
                PluginManifest.PluginType.JAVA, "dev.hmclnex.test.Plugin"
        );

        assertEquals(PluginInstallPlan.Action.UPDATE, PluginStorePage.rootInstallationAction(installed));
        assertFalse(PluginStorePage.presentationFor(new PluginStoreSnapshot(2, List.of())).showPluginRows());
    }

    /// Creates an aggregate snapshot containing one successful winner and one unavailable enabled source.
    ///
    /// @return degraded aggregate snapshot
    private static PluginStoreSnapshot degradedSnapshot() {
        PluginSource sourceA = new PluginSource(
                "source_a", "https://source-a.example.test/plugins.json", "Source A", true, false);
        PluginSource sourceB = new PluginSource(
                "source_b", "https://source-b.example.test/plugins.json", "Source B", true, false);
        return new PluginStoreSnapshot(1, List.of(
                successfulResult(sourceA, "dev.hmclnex.aggregate", "Aggregate Plugin"),
                PluginSourceLoadResult.failed(sourceB, 1, new IOException("offline"))
        ));
    }

    /// Creates an aggregate snapshot with the same plugin ID published by two priority-ordered sources.
    ///
    /// @return conflict-bearing aggregate snapshot
    private static PluginStoreSnapshot conflictingSnapshot() {
        PluginSource sourceA = new PluginSource(
                "source_a", "https://source-a.example.test/plugins.json", "Source A", true, false);
        PluginSource sourceB = new PluginSource(
                "source_b", "https://source-b.example.test/plugins.json", "Source B", true, false);
        return new PluginStoreSnapshot(1, List.of(
                successfulResult(sourceA, "dev.hmclnex.conflict", "Source A Plugin"),
                successfulResult(sourceB, "dev.hmclnex.conflict", "Source B Plugin")
        ));
    }

    /// Resolves an aggregate plan whose root and dependency intentionally come from different winners.
    ///
    /// @return source-aware dependency plan
    private static PluginInstallPlan planWithTwoRemoteSources() throws IOException {
        PluginSource sourceA = new PluginSource(
                "source_a", "https://source-a.example.test/plugins.json", "Source A", true, false);
        PluginSource sourceB = new PluginSource(
                "source_b", "https://source-b.example.test/plugins.json", "Source B", true, false);
        PluginStoreItem root = itemWithManifest(
                sourceA,
                "dev.hmclnex.root",
                "Root",
                "[{\"id\":\"dev.hmclnex.dependency\",\"version\":\"*\"}]",
                "1"
        );
        PluginStoreItem dependency = itemWithManifest(
                sourceB, "dev.hmclnex.dependency", "Dependency", "[]", "2"
        );
        PluginStoreManifest.PluginVersionEntry rootVersion = Objects.requireNonNull(
                Objects.requireNonNull(root.getManifest()).getVersion("1.0.0")
        );
        return new PluginStoreDependencyResolver(Map.of(
                root.getEntry().getId(), root,
                dependency.getEntry().getId(), dependency
        )).resolveInstallPlan(root.getEntry().getId(), rootVersion, Map.of(), Map.of(), Map.of());
    }

    /// Creates one successful source result for a registry item without a repository manifest.
    ///
    /// @param source source owning the item
    /// @param pluginId stable plugin ID
    /// @param name display name
    /// @return successful source result
    private static PluginSourceLoadResult successfulResult(PluginSource source, String pluginId, String name) {
        PluginStoreItem item = successfulItem(source, pluginId, name);
        return PluginSourceLoadResult.success(
                source, 1, List.of(item), 1, item.getRegistry(), item.getSourceManager());
    }

    /// Creates one source-bound registry item without a repository manifest.
    ///
    /// @param source source owning the item
    /// @param pluginId stable plugin ID
    /// @param name display name
    /// @return source-bound catalog item
    private static PluginStoreItem successfulItem(PluginSource source, String pluginId, String name) {
        PluginStoreRegistry registry = Objects.requireNonNull(JsonUtils.GSON.fromJson("""
                {
                  "schemaVersion": 1,
                  "name": "%s",
                  "plugins": [{
                    "id": "%s",
                    "name": "%s",
                    "manifestUrl": "https://plugins.example.test/%s.json"
                  }]
                }
                """.formatted(source.getAlias(), pluginId, name, pluginId), PluginStoreRegistry.class));
        return new PluginStoreItem(source, registry, new PluginStoreManager(), registry.getPlugins().get(0), null);
    }

    /// Creates one source-bound catalog item with a complete repository manifest.
    ///
    /// @param source source owning the item
    /// @param pluginId stable plugin ID
    /// @param name display name
    /// @param dependenciesJson dependency JSON array
    /// @param hashDigit hexadecimal checksum digit
    /// @return source-bound catalog item
    /// @throws IOException if the generated manifest is invalid
    private static PluginStoreItem itemWithManifest(
            PluginSource source,
            String pluginId,
            String name,
            String dependenciesJson,
            String hashDigit
    ) throws IOException {
        PluginStoreItem item = successfulItem(source, pluginId, name);
        PluginStoreManifest manifest = Objects.requireNonNull(JsonUtils.GSON.fromJson("""
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "versions": [{
                    "version": "1.0.0",
                    "packageUrl": "https://plugins.example.test/%s.npl",
                    "sha256": "%s",
                    "pluginApiVersion": 4,
                    "permissions": [],
                    "requiredPermissions": [],
                    "launcherVersion": "*",
                    "dependencies": %s,
                    "size": 1
                  }]
                }
                """.formatted(pluginId, pluginId, hashDigit.repeat(64), dependenciesJson), PluginStoreManifest.class));
        manifest.validate(pluginId);
        return new PluginStoreItem(source, item.getRegistry(), item.getSourceManager(), item.getEntry(), manifest);
    }

    /// Requires every runtime state used by plugin rows and permission details to have a localized label.
    @Test
    public void everyRuntimeStatusHasLocalizedLabel() {
        for (PluginRuntimeStatus status : PluginRuntimeStatus.values()) {
            assertFalse(PluginPermissionManagementPage.runtimeStatusLabel(status).isBlank());
        }
        assertFalse(org.jackhuang.hmcl.util.i18n.I18n.hasKey("plugin.permissions.scope_hint"));
    }
}

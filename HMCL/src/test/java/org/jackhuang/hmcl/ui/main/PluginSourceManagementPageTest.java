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

import org.jackhuang.hmcl.plugin.store.PluginSource;
import org.jackhuang.hmcl.plugin.store.PluginSourceLoadResult;
import org.jackhuang.hmcl.plugin.store.PluginStoreItem;
import org.jackhuang.hmcl.plugin.store.PluginStoreManager;
import org.jackhuang.hmcl.plugin.store.PluginStoreRegistry;
import org.jackhuang.hmcl.plugin.store.PluginStoreSnapshot;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies compact source presentation and source-specific action availability.
@NotNullByDefault
public final class PluginSourceManagementPageTest {
    /// Prefers aliases and remote registry names before deriving a safe compact fallback from the source URL.
    @Test
    public void sourceNameUsesAliasThenRemoteNameThenCompactFallback() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json",
                "My plugins", true, false);

        assertEquals("My plugins", PluginSourceManagementPage.displayName(source, "Remote"));
        assertEquals("Remote", PluginSourceManagementPage.displayName(
                source.withConfiguration(source.getUrl(), null), "Remote"));
        assertEquals("plugins.example.org / plugins.json",
                PluginSourceManagementPage.displayName(
                        source.withConfiguration(source.getUrl(), null), null));
    }

    /// Exposes no configuration or deletion controls for the fixed official source.
    @Test
    public void officialActionsExcludeEditAndDelete() {
        PluginSource official = new PluginSource(
                PluginSource.OFFICIAL_ID,
                org.jackhuang.hmcl.plugin.store.PluginStoreManager.DEFAULT_REGISTRY_URL,
                null,
                true,
                true
        );
        assertEquals(Set.of(PluginSourceManagementPage.Action.TEST, PluginSourceManagementPage.Action.DETAILS),
                PluginSourceManagementPage.secondaryActions(official));
    }

    /// Keeps full URLs out of compact rows while retaining them in an explicit source details model.
    @Test
    public void compactRowsHideUrlsWhileDetailsRetainThem() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json?credential=secret",
                null, true, false);

        PluginSourceManagementPage.SourceRow row = PluginSourceManagementPage.sourceRow(source, "Remote", null);
        PluginSourceManagementPage.SourceDetails details = PluginSourceManagementPage.sourceDetails(source, "Remote", null);

        assertFalse(row.title().contains(source.getUrl()));
        assertFalse(row.subtitle().contains(source.getUrl()));
        assertTrue(details.url().contains(source.getUrl()));
    }

    /// Requires network validation for adds and URL changes but lets alias-only edits persist directly.
    @Test
    public void sourceSavesPreviewOnlyNewOrChangedUrls() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", "Before", true, false);

        assertTrue(PluginSourceManagementPage.requiresPreview(null, source.getUrl()));
        assertTrue(PluginSourceManagementPage.requiresPreview(source, "https://plugins.example.org/changed.json"));
        assertFalse(PluginSourceManagementPage.requiresPreview(source, source.getUrl()));
    }

    /// Reserves compact count and duration fields before a source has produced its first load result.
    @Test
    public void compactRowsIncludeCountAndDurationBeforeFirstLoad() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", null, true, false);

        PluginSourceManagementPage.SourceRow row = PluginSourceManagementPage.sourceRow(source, null, null);

        assertTrue(row.subtitle().contains("0 " + org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.plugins")));
        assertTrue(row.subtitle().endsWith("· -"));
    }

    /// Includes normalized source configuration and validated registry metadata in the save preview.
    @Test
    public void sourcePreviewIncludesConfigurationRegistryMetadataAndPluginCount() {
        assertEquals(
                "URL: https://plugins.example.org/catalog/plugins.json\nAlias: Local\nRemote\n"
                        + "A curated plugin registry\nHomepage: plugins.example.org\n3 "
                        + org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.plugins"),
                PluginSourceManagementPage.previewMessage(
                        "https://plugins.example.org/catalog/plugins.json",
                        "Local",
                        "Remote",
                        "A curated plugin registry",
                        "plugins.example.org",
                        3
                )
        );
    }

    /// Prefers a configuration-matching manual source success over an older aggregate failure and its duration/count.
    @Test
    public void currentManualResultsOverrideSnapshotFailureCountAndDuration() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", null, true, false);
        PluginSourceLoadResult aggregate = PluginSourceLoadResult.failed(source, 10, new IOException("aggregate failure"));
        PluginStoreRegistry registry = JsonUtils.GSON.fromJson("""
                {
                  "schemaVersion": 1,
                  "name": "Manual",
                  "plugins": [{
                    "id": "dev.hmclnex.manual",
                    "name": "Manual",
                    "manifestUrl": "https://plugins.example.org/manifest.json"
                  }]
                }
                """, PluginStoreRegistry.class);
        PluginStoreManager manager = new PluginStoreManager();
        PluginStoreItem item = new PluginStoreItem(
                source, registry, manager, registry.getPlugins().get(0), null);
        PluginSourceLoadResult manual = PluginSourceLoadResult.success(
                source, 20, List.of(item), 1, registry, manager);
        PluginStoreSnapshot snapshot = new PluginStoreSnapshot(1, List.of(aggregate));

        Map<String, PluginSourceLoadResult> merged = PluginSourceManagementPage.mergeSourceResults(
                snapshot,
                Map.of(source.getId(), manual),
                List.of(source)
        );

        assertEquals(manual, merged.get(source.getId()));
        assertEquals(PluginSourceLoadResult.Status.PARTIAL_FAILURE, merged.get(source.getId()).getStatus());
        assertEquals(20, merged.get(source.getId()).getDurationMillis());
        assertEquals(1, merged.get(source.getId()).getItems().size());
    }

    /// Discards aggregate and manual outcomes whose source URL no longer matches persisted configuration.
    @Test
    public void sourceResultMergeDiscardsOutcomesAfterSourceMutation() {
        PluginSource original = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", null, true, false);
        PluginSource current = original.withConfiguration("https://plugins.example.org/changed.json", null);
        PluginSourceLoadResult stale = PluginSourceLoadResult.failed(original, 10, new IOException("stale"));

        assertTrue(PluginSourceManagementPage.mergeSourceResults(
                new PluginStoreSnapshot(1, List.of(stale)),
                Map.of(original.getId(), stale),
                List.of(current)
        ).isEmpty());
    }

    /// Gives custom source manual outcomes first priority for installed-plugin removal impact checks.
    @Test
    public void manualSourceItemsArePreferredForInstalledPluginDeletionWarning() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", null, true, false);

        assertTrue(PluginSourceManagementPage.shouldWarnInstalledPlugins(
                source,
                Map.of(source.getId(), Set.of("dev.hmclnex.installed")),
                Map.of(),
                Set.of("dev.hmclnex.installed")
        ));
    }

    /// Exposes explicit source diagnostics in details while retaining the full URL only there.
    @Test
    public void sourceDetailsIncludeAliasRegistryMetadataAndSourceDiagnostics() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", "Local", true, false);
        PluginStoreRegistry registry = JsonUtils.GSON.fromJson("""
                {
                  "schemaVersion": 1,
                  "name": "Remote",
                  "description": "A curated plugin registry",
                  "homepageUrl": "https://plugins.example.org/home",
                  "plugins": []
                }
                """, PluginStoreRegistry.class);
        PluginStoreManager manager = new PluginStoreManager();
        PluginSourceLoadResult result = PluginSourceLoadResult.success(
                source, 42, List.of(), 0, registry, manager);
        PluginStoreSnapshot snapshot = new PluginStoreSnapshot(1, List.of(result));

        PluginSourceManagementPage.SourceDetails details = PluginSourceManagementPage.sourceDetails(
                source, "Remote", result, 2, snapshot);

        assertEquals("Local", details.title());
        assertTrue(details.message().contains(source.getUrl()));
        assertTrue(details.message().contains("Alias: Local"));
        assertTrue(details.message().contains("Registry: Remote"));
        assertTrue(details.message().contains("Description: A curated plugin registry"));
        assertTrue(details.message().contains("Homepage: plugins.example.org"));
        assertTrue(details.message().contains("Priority: 2"));
        assertTrue(details.message().contains("Duration: 42 ms"));
        assertTrue(details.message().contains("Plugins: 0"));
        assertTrue(details.message().contains("Partial manifest failures: 0"));
        assertTrue(details.message().contains("Conflicts: 0"));
        assertTrue(details.message().contains("Type: Third-party"));
        assertTrue(details.message().contains("Enabled: true"));
    }

    /// Keeps URL-bearing failure diagnostics out of compact rows while retaining them in details.
    @Test
    public void failedCompactRowsHideDiagnosticUrlWhileDetailsShowIt() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", null, true, false);
        PluginSourceLoadResult failed = PluginSourceLoadResult.failed(
                source,
                7,
                new IOException("Cannot read https://plugins.example.org/catalog/plugins.json?token=secret")
        );

        PluginSourceManagementPage.SourceRow row = PluginSourceManagementPage.sourceRow(source, null, failed);
        PluginSourceManagementPage.SourceDetails details = PluginSourceManagementPage.sourceDetails(source, null, failed);

        assertFalse(row.subtitle().contains("https://"));
        assertTrue(row.subtitle().contains("FAILED"));
        assertTrue(details.message().contains("Failure: Cannot read https://plugins.example.org/catalog/plugins.json"));
        assertTrue(details.message().contains("Type: Third-party"));
        assertTrue(details.message().contains("Enabled: true"));
    }

    /// Presents separate enabled and source result statuses for compact source rows.
    @Test
    public void compactRowsRenderEnabledAndActualLoadStatuses() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", null, false, false);
        PluginSourceLoadResult disabled = PluginSourceLoadResult.disabled(source);

        PluginSourceManagementPage.SourceRow unavailable = PluginSourceManagementPage.sourceRow(source, null, null);
        PluginSourceManagementPage.SourceRow loaded = PluginSourceManagementPage.sourceRow(source, null, disabled);

        assertTrue(unavailable.subtitle().contains(org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.disabled")));
        assertTrue(unavailable.subtitle().contains("Unavailable"));
        assertTrue(loaded.subtitle().contains("DISABLED"));
    }

    /// Rejects stale source-test completions after a newer request or source configuration mutation.
    @Test
    public void staleSourceTestsCannotPublishAfterNewerRequestOrConfigurationChange() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", null, true, false);

        assertFalse(PluginSourceManagementPage.canPublishTestResult(
                source, List.of(source), 1, 2, 1, 1));
        assertFalse(PluginSourceManagementPage.canPublishTestResult(
                source, List.of(source), 2, 2, 1, 2));
        assertFalse(PluginSourceManagementPage.canPublishTestResult(
                source, List.of(source.withConfiguration("https://plugins.example.org/changed.json", null)),
                2, 2, 1, 1));
        assertTrue(PluginSourceManagementPage.canPublishTestResult(
                source, List.of(source), 2, 2, 1, 1));
    }

    /// Moves a dragged source directly before its drop target while retaining every configured ID exactly once.
    @Test
    public void draggedSourceMovesBeforeDropTarget() {
        assertEquals(
                java.util.List.of("third", "first", "second"),
                PluginSourceManagementPage.reorderedIds(
                        java.util.List.of("first", "second", "third"),
                        "third",
                        "first"
                )
        );
    }

    /// Leaves the configured order untouched when a drag cannot identify both current source IDs.
    @Test
    public void invalidDraggedSourceLeavesOrderUnchanged() {
        assertEquals(
                java.util.List.of("first", "second"),
                PluginSourceManagementPage.reorderedIds(
                        java.util.List.of("first", "second"),
                        "missing",
                        "second"
                )
        );
    }

}

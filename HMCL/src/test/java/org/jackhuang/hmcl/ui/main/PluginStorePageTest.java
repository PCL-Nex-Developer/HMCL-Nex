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
import org.jackhuang.hmcl.plugin.store.PluginStoreManager;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /// Keeps source load durations nonnegative for source-management presentation.
    @Test
    public void storeLoadResultRejectsNegativeDuration() {
        assertThrows(IllegalArgumentException.class,
                () -> new PluginStorePage.StoreLoadResult(new PluginStoreManager(), List.of(), -1));
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

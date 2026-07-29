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
package org.jackhuang.hmcl.ui.main;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXSpinner;
import com.jfoenix.controls.JFXTextField;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import org.jackhuang.hmcl.plugin.LocalPluginInspection;
import org.jackhuang.hmcl.plugin.PluginDependency;
import org.jackhuang.hmcl.plugin.PluginInstallationPlanningSnapshot;
import org.jackhuang.hmcl.plugin.PluginManager;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.PluginRuntimeStatus;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.plugin.store.PluginInstallPlan;
import org.jackhuang.hmcl.plugin.store.PluginSource;
import org.jackhuang.hmcl.plugin.store.PluginStoreItem;
import org.jackhuang.hmcl.plugin.store.PluginStoreManager;
import org.jackhuang.hmcl.plugin.store.PluginStorePreferences;
import org.jackhuang.hmcl.plugin.store.PluginStoreManifest;
import org.jackhuang.hmcl.plugin.store.PluginStoreRegistry;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.HTMLRenderer;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.PageAware;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.jackhuang.hmcl.ui.ToolbarListPageSkin.createToolbarButton2;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Presents the remote plugin registry, version details, favorites, and dependency-aware installation workflow.
@NotNullByDefault
public class PluginStorePage extends VBox implements DecoratorPage, PageAware {
    /// Sentinel value used by the category selector for an unfiltered view.
    private static final String ALL_CATEGORIES = "__all__";

    /// Maximum weighted text width reserved for a two-line description at the 580px layout.
    private static final int DESCRIPTION_DISPLAY_UNITS = 72;

    /// Maximum weighted plugin-name width that leaves the minimum-width trailing actions fully visible.
    private static final int TITLE_DISPLAY_UNITS = 36;

    /// Error presentation state used by incompatible plugin metadata rows.
    private static final PseudoClass PSEUDO_ERROR = PseudoClass.getPseudoClass("error");

    /// Decorator navigation state for the plugin store page.
    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("plugin.store")));

    /// Remote registry, compatibility, README, and download service for the fixed official source.
    private PluginStoreManager storeManager = new PluginStoreManager();

    /// Page-owned persistence for favorite plugin IDs.
    private final PluginStorePreferences storePreferences = new PluginStorePreferences(Metadata.HMCL_LOCAL_HOME);

    /// Process-wide plugin lifecycle manager used to inspect and publish downloaded packages.
    private final PluginManager pluginManager = PluginManager.getInstance();

    /// Filtered visual list of HMCL plugin rows.
    private final ComponentList pluginList = new ComponentList();

    /// Activity indicator displayed while the registry is loading.
    private final JFXSpinner loadingSpinner = new JFXSpinner();

    /// Registry and filtering status text.
    private final Label statusLabel = new Label();

    /// Free-text plugin search field.
    private final JFXTextField searchField = new JFXTextField();

    /// Category filter selector.
    private final JFXComboBox<CategoryItem> categoryBox = new JFXComboBox<>();

    /// Whether the current result is limited to persisted favorite plugins.
    private final BooleanProperty favoritesOnly = new SimpleBooleanProperty();

    /// Fixed star command that toggles the favorites-only filter.
    private final JFXButton favoritesOnlyButton = new JFXButton();

    /// Resolved registry items before client-side filtering.
    private final List<PluginStoreItem> allItems = new ArrayList<>();

    /// Readable installed package manifests, including plugins whose lifecycle failed to load.
    private @Unmodifiable Map<String, PluginManifest> installedManifests = Map.of();

    /// Future installed manifests used by the dependency planner after pending removals complete.
    private @Unmodifiable Map<String, PluginManifest> installPlanningManifests = Map.of();

    /// Installed-package read failure, or `null` when update and installation state is trustworthy.
    private @Nullable String installedManifestFailure;

    /// Monotonic request generation preventing stale registry loads from replacing a newer selection.
    private long storeLoadGeneration;

    /// Whether the current generation has published a complete registry result.
    private boolean storeLoaded;

    /// Current generation's load failure, or `null` outside the error state.
    private @Nullable String storeLoadFailure;

    /// Whether the first visible-page registry request has already been started.
    private boolean initialLoadRequested;

    /// Most recently opened details page, retained so a completed installation can refresh its local state.
    private @Nullable PluginDetailsPage activeDetailsPage;

    /// Creates the HMCL toolbar, filters, status area, and scrollable plugin list.
    public PluginStorePage() {
        getStyleClass().add("gray-background");
        setSpacing(10);
        setPadding(new Insets(10));

        HBox toolbar = new HBox(12);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setMinHeight(44);
        toolbar.setPrefHeight(44);

        searchField.setPromptText(i18n("plugin.store.search"));
        searchField.setMinWidth(120);
        searchField.setPrefHeight(36);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((observable, oldValue, newValue) -> applyFilter());

        JFXButton refreshButton = createIconButton(
                i18n("plugin.store.refresh"),
                SVG.REFRESH,
                this::loadPluginStore
        );
        JFXButton settingsButton = createIconButton(
                i18n("plugin.store.settings"),
                SVG.SETTINGS,
                () -> {
                }
        );
        settingsButton.setDisable(true);
        settingsButton.setTooltip(new Tooltip(i18n("plugin.store.settings")));
        settingsButton.setAccessibleHelp(i18n("plugin.store.settings"));
        favoritesOnlyButton.getStyleClass().add("jfx-tool-bar-button");
        favoritesOnlyButton.setMinSize(40, 40);
        favoritesOnlyButton.setPrefSize(40, 40);
        favoritesOnlyButton.setMaxSize(40, 40);
        favoritesOnlyButton.setOnAction(event -> favoritesOnly.set(!favoritesOnly.get()));
        favoritesOnly.addListener((observable, oldValue, newValue) -> {
            refreshFavoritesFilterButton();
            applyFilter();
        });
        refreshFavoritesFilterButton();
        toolbar.getChildren().addAll(searchField, favoritesOnlyButton, refreshButton, settingsButton);

        FlowPane filters = new FlowPane(12, 8);
        filters.setAlignment(Pos.CENTER_LEFT);
        filters.prefWrapLengthProperty().bind(widthProperty().subtract(20));

        categoryBox.setMinWidth(140);
        categoryBox.setPrefWidth(170);
        categoryBox.valueProperty().addListener((var observable, @Nullable var oldValue, @Nullable var newValue) ->
                applyFilter());

        filters.getChildren().add(categoryBox);

        HBox statusRow = new HBox(statusLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusLabel.setWrapText(true);
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);
        statusLabel.visibleProperty().addListener((observable, oldValue, visible) ->
                statusLabel.setManaged(visible));
        statusRow.visibleProperty().bind(statusLabel.visibleProperty());
        statusRow.managedProperty().bind(statusRow.visibleProperty());
        loadingSpinner.setVisible(false);
        loadingSpinner.setManaged(false);
        loadingSpinner.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        loadingSpinner.setAccessibleText(i18n("plugin.store.loading"));
        loadingSpinner.visibleProperty().addListener((observable, oldValue, visible) ->
                loadingSpinner.setManaged(visible));

        pluginList.getStyleClass().add("no-padding");
        ScrollPane scrollPane = new ScrollPane(pluginList);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        FXUtils.smoothScrolling(scrollPane);
        FXUtils.setOverflowHidden(scrollPane, 8);

        StackPane contentPane = new StackPane(scrollPane, loadingSpinner);
        VBox.setVgrow(contentPane, Priority.ALWAYS);

        getChildren().setAll(toolbar, filters, statusRow, contentPane);
    }

    /// Creates a fixed icon-only toolbar command with an accessible name and hover explanation.
    ///
    /// @param tooltip localized command description
    /// @param icon familiar command glyph
    /// @param action command callback
    /// @return configured icon button
    private static JFXButton createIconButton(String tooltip, SVG icon, Runnable action) {
        JFXButton button = createToolbarButton2(null, icon, action);
        button.setMinSize(40, 40);
        button.setPrefSize(40, 40);
        button.setMaxSize(40, 40);
        button.setTooltip(new Tooltip(tooltip));
        button.setAccessibleText(tooltip);
        return button;
    }

    /// Synchronizes the favorites filter glyph, accent, tooltip, and accessibility text.
    private void refreshFavoritesFilterButton() {
        boolean selected = favoritesOnly.get();
        favoritesOnlyButton.setText(selected ? "\u2605" : "\u2606");
        String tooltip = i18n(selected
                ? "plugin.store.favorite.show_all"
                : "plugin.store.favorite.only");
        favoritesOnlyButton.setTooltip(new Tooltip(tooltip));
        favoritesOnlyButton.setAccessibleText(tooltip);
        favoritesOnlyButton.setStyle(selected ? "-fx-text-fill: -monet-primary;" : null);
    }

    /// Replaces the plugin rows with one explicit loading, error, empty, or no-result state.
    ///
    /// @param icon state glyph
    /// @param title localized state title
    /// @param subtitle optional localized explanation
    private void showStoreMessage(SVG icon, String title, @Nullable String subtitle) {
        LineButton message = new LineButton();
        message.setLeading(icon);
        message.setTitle(title);
        message.setSubtitle(subtitle);
        message.setMinHeight(72);
        message.setMouseTransparent(true);
        pluginList.getContent().setAll(message);
    }

    /// Loads the fixed official source and its repository manifests on a background thread.
    private void loadPluginStore() {
        long generation = ++storeLoadGeneration;
        storeLoaded = false;
        storeLoadFailure = null;
        statusLabel.setVisible(false);
        statusLabel.setText("");
        pluginList.getContent().clear();
        loadingSpinner.setVisible(true);
        allItems.clear();
        refreshInstalledManifests();

        PluginStoreManager requestManager = new PluginStoreManager();
        PluginSource officialSource = new PluginSource(
                PluginSource.OFFICIAL_ID,
                PluginStoreManager.DEFAULT_REGISTRY_URL,
                null,
                true,
                true
        );

        Task.supplyAsync(() -> {
            try {
                requestManager.loadSource(officialSource);
                return new StoreLoadResult(requestManager, requestManager.getStoreItems());
            } catch (IOException exception) {
                LOG.error("Failed to load plugin store", exception);
                throw new RuntimeException(exception);
            }
        }).whenComplete(Schedulers.javafx(), (@Nullable var result, @Nullable var exception) -> {
            if (generation != storeLoadGeneration) {
                return;
            }
            loadingSpinner.setVisible(false);
            if (exception != null) {
                String message = failureMessage(exception);
                storeLoadFailure = message;
                showStoreMessage(
                        SVG.ERROR,
                        i18n("plugin.store.load_failed"),
                        i18n("plugin.store.load_failed.detail", message)
                );
                return;
            }
            storeManager = result.manager;
            storeLoaded = true;
            storeLoadFailure = null;
            allItems.clear();
            allItems.addAll(result.items);
            refreshCategories();
            applyFilter();
        }).start();
    }

    /// Refreshes disk-level installation state without depending on successful lifecycle loading.
    private void refreshInstalledManifests() {
        try {
            @Unmodifiable Map<String, PluginManifest> published = pluginManager.getPublishedPluginManifests();
            @Unmodifiable Map<String, PluginManifest> planning = pluginManager.getInstalledManifests();
            installedManifests = published;
            installPlanningManifests = planning;
            installedManifestFailure = null;
        } catch (IOException exception) {
            installedManifests = Map.of();
            installPlanningManifests = Map.of();
            installedManifestFailure = failureMessage(exception);
            LOG.warning("Failed to read installed plugin manifests for the store", exception);
        }
    }

    /// Rebuilds category choices while preserving the previously selected category when possible.
    private void refreshCategories() {
        @Nullable CategoryItem selected = categoryBox.getSelectionModel().getSelectedItem();
        Set<String> categories = allItems.stream()
                .map(item -> item.getEntry().getCategory())
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(TreeSet::new));

        List<CategoryItem> items = new ArrayList<>();
        items.add(new CategoryItem(ALL_CATEGORIES, i18n("plugin.store.category.all")));
        categories.forEach(category -> items.add(new CategoryItem(category, category)));
        categoryBox.getItems().setAll(items);

        if (selected == null) {
            categoryBox.getSelectionModel().selectFirst();
            return;
        }
        categoryBox.getItems().stream()
                .filter(item -> item.value.equals(selected.value))
                .findFirst()
                .ifPresentOrElse(
                        categoryBox.getSelectionModel()::select,
                        categoryBox.getSelectionModel()::selectFirst
        );
    }

    /// Applies search, category, and favorites filters without resetting any filter control.
    private void applyFilter() {
        if (!storeLoaded) {
            @Nullable String failure = storeLoadFailure;
            if (failure == null) {
                pluginList.getContent().clear();
            } else {
                showStoreMessage(
                        SVG.ERROR,
                        i18n("plugin.store.load_failed"),
                        i18n("plugin.store.load_failed.detail", failure)
                );
            }
            return;
        }

        pluginList.getContent().clear();
        String keyword = Optional.ofNullable(searchField.getText())
                .orElse("")
                .trim()
                .toLowerCase(Locale.ROOT);
        @Nullable CategoryItem category = categoryBox.getSelectionModel().getSelectedItem();

        @Unmodifiable List<PluginStoreItem> filtered = allItems.stream()
                .filter(item -> matchesCategory(item, category))
                .filter(item -> matchesKeyword(item, keyword))
                .filter(item -> !favoritesOnly.get()
                        || storePreferences.isFavorite(item.getEntry().getId()))
                .sorted((left, right) -> displayName(left).compareToIgnoreCase(displayName(right)))
                .toList();

        if (filtered.isEmpty()) {
            boolean registryEmpty = allItems.isEmpty();
            showStoreMessage(
                    SVG.INFO,
                    i18n(registryEmpty ? "plugin.store.empty" : "plugin.store.no_result"),
                    i18n(registryEmpty
                            ? "plugin.store.empty.description"
                            : "plugin.store.no_result.description")
            );
        } else {
            filtered.forEach(item -> pluginList.getContent().add(createPluginRow(item)));
        }

        @Nullable PluginStoreRegistry registry = storeManager.getRegistry();
        String registryName = registry == null ? PluginStoreManager.DEFAULT_REGISTRY_URL : registry.getName();
        String loadedStatus = i18n("plugin.store.loaded") + ": " + filtered.size() + "/" + allItems.size()
                + " " + i18n("plugin.store.plugins") + " - " + registryName;
        @Nullable String installedFailure = installedManifestFailure;
        statusLabel.setText(installedFailure == null
                ? loadedStatus
                : loadedStatus + "\n" + i18n("plugin.installed.load_failed") + ": " + installedFailure);
        statusLabel.setVisible(true);
    }

    /// Tests one item against the selected category.
    ///
    /// @param item store item
    /// @param category selected category or `null`
    /// @return whether the item belongs in the category result
    private static boolean matchesCategory(PluginStoreItem item, @Nullable CategoryItem category) {
        return category == null
                || ALL_CATEGORIES.equals(category.value)
                || Objects.equals(category.value, item.getEntry().getCategory());
    }

    /// Tests searchable registry metadata against a normalized keyword.
    ///
    /// @param item store item
    /// @param keyword lower-case keyword
    /// @return whether any searchable field contains the keyword
    private static boolean matchesKeyword(PluginStoreItem item, String keyword) {
        if (keyword.isEmpty()) {
            return true;
        }
        PluginStoreRegistry.PluginStoreEntry entry = item.getEntry();
        return contains(entry.getId(), keyword)
                || contains(entry.getName(), keyword)
                || contains(entry.getAuthor(), keyword)
                || contains(entry.getDescription(), keyword)
                || contains(entry.getCategory(), keyword)
                || entry.getTags().stream().anyMatch(tag -> contains(tag, keyword))
                || entry.getCapabilities().stream().anyMatch(capability -> contains(capability, keyword));
    }

    /// Performs a null-aware case-insensitive substring test.
    ///
    /// @param value source text or `null`
    /// @param keyword normalized keyword
    /// @return whether the source contains the keyword
    private static boolean contains(@Nullable String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    /// Returns the preferred display name for sorting and presentation.
    ///
    /// @param item store item
    /// @return registry name or plugin ID
    private static String displayName(PluginStoreItem item) {
        String name = item.getEntry().getName();
        return StringUtils.isBlank(name) ? item.getEntry().getId() : name;
    }

    /// Builds one native HMCL list row using the newest version compatible with the current runtime.
    ///
    /// @param item resolved store item
    /// @return plugin navigation row
    private LineButton createPluginRow(PluginStoreItem item) {
        PluginStoreRegistry.PluginStoreEntry entry = item.getEntry();
        PluginStoreManager sourceManager = item.getSourceManager();
        @Nullable PluginStoreManifest.PluginVersionEntry compatibleVersion =
                sourceManager.getLatestCompatibleVersion(item.getManifest());
        @Nullable PluginManifest installed = installedManifests.get(entry.getId());
        boolean hasUpdate = sourceManager.hasUpdate(installed, compatibleVersion);
        boolean manifestLoadFailed = item.getManifest() == null;
        boolean installedStateUnavailable = installedManifestFailure != null;
        @Nullable PluginRuntimeStatus runtimeStatus = installed == null
                ? null
                : pluginManager.getPluginRuntimeStatus(entry.getId());

        LineButton row = LineButton.createNavigationButton();
        row.getStyleClass().add("plugin-store-row");
        row.setLeading(SVG.EXTENSION);
        row.setTitle(summarizeTitle(displayName(item)));
        row.setLargeTitle(true);
        row.setSubtitle(buildPluginRowSubtitle(item, compatibleVersion));
        row.setMinHeight(112);
        String status;
        if (manifestLoadFailed) {
            status = i18n("plugin.store.manifest_load_failed");
        } else if (installedStateUnavailable) {
            status = i18n("plugin.installed.load_failed");
        } else if (hasUpdate) {
            status = runtimeStatus == null
                    ? i18n("plugin.store.update_available")
                    : i18n("plugin.store.update_available") + "\n" + installedRuntimeStatus(runtimeStatus);
        } else if (installed != null) {
            status = installedRuntimeStatus(Objects.requireNonNull(runtimeStatus));
        } else if (compatibleVersion == null) {
            status = i18n("plugin.store.version.incompatible");
        } else {
            status = versionText(compatibleVersion);
        }
        row.setTrailingText(null);
        row.setTrailingIcon(createPluginTrailingActions(entry.getId(), status, hasUpdate));
        row.setOnAction(event -> showPluginDetails(item));
        return row;
    }

    /// Returns the user-facing store status for one installed runtime artifact.
    ///
    /// A successfully enabled artifact retains the familiar store-level `Installed` label. Every non-running state
    /// uses the shared runtime-status translation so blocked, failed, staged, disabled, and pending-removal packages
    /// remain distinguishable.
    ///
    /// @param status authoritative artifact runtime status
    /// @return localized store row status
    private static String installedRuntimeStatus(PluginRuntimeStatus status) {
        return status == PluginRuntimeStatus.ENABLED
                ? i18n("plugin.store.installed")
                : PluginPermissionManagementPage.runtimeStatusLabel(status);
    }

    /// Builds the layered description, author, category, and version summary shown in one store row.
    ///
    /// @param item resolved store item
    /// @param version newest compatible version or `null`
    /// @return wrapped row subtitle
    private static String buildPluginRowSubtitle(
            PluginStoreItem item,
            @Nullable PluginStoreManifest.PluginVersionEntry version
    ) {
        PluginStoreRegistry.PluginStoreEntry entry = item.getEntry();
        List<String> metadataLines = new ArrayList<>();
        String description = summarizeDescription(entry.getDescription());
        if (StringUtils.isNotBlank(entry.getAuthor())) {
            metadataLines.add(i18n("plugin.author") + ": " + entry.getAuthor());
        }
        if (StringUtils.isNotBlank(entry.getCategory())) {
            metadataLines.add(i18n("plugin.store.category") + ": " + entry.getCategory());
        }
        String versionSummary;
        if (item.getManifest() == null) {
            versionSummary = i18n("plugin.store.manifest_load_failed");
        } else if (version == null) {
            versionSummary = i18n("plugin.store.version.incompatible");
        } else {
            versionSummary = versionText(version);
        }
        metadataLines.add(i18n("plugin.store.version") + ": " + versionSummary);
        return composePluginRowSubtitle(description, metadataLines);
    }

    /// Separates descriptive prose from scan-friendly metadata without producing empty edge lines.
    ///
    /// @param description normalized description or an empty string
    /// @param metadataLines author, category, and version lines
    /// @return subtitle with a blank separator only when both sections are present
    static String composePluginRowSubtitle(String description, List<String> metadataLines) {
        if (description.isEmpty()) {
            return String.join("\n", metadataLines);
        }
        if (metadataLines.isEmpty()) {
            return description;
        }
        return description + "\n\n" + String.join("\n", metadataLines);
    }

    /// Normalizes registry prose and bounds it to two narrow-window display lines.
    ///
    /// @param description registry description or `null`
    /// @return compact one-paragraph summary
    static String summarizeDescription(@Nullable String description) {
        if (StringUtils.isBlank(description)) {
            return "";
        }
        return summarizeDisplayText(Objects.requireNonNull(description), DESCRIPTION_DISPLAY_UNITS);
    }

    /// Normalizes and bounds a plugin name so fixed trailing actions retain their minimum-width allocation.
    ///
    /// @param title registry plugin name
    /// @return compact single-paragraph row title
    static String summarizeTitle(String title) {
        return summarizeDisplayText(title, TITLE_DISPLAY_UNITS);
    }

    /// Normalizes one row string and truncates it by deterministic narrow and wide display units.
    ///
    /// @param value source row text
    /// @param maximumUnits maximum weighted display width
    /// @return normalized and possibly ellipsized text
    private static String summarizeDisplayText(String value, int maximumUnits) {
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (displayUnits(normalized) <= maximumUnits) {
            return normalized;
        }

        StringBuilder result = new StringBuilder();
        int usedUnits = 0;
        int contentLimit = maximumUnits - 2;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            int codePointUnits = codePoint <= 0x7f ? 1 : 2;
            if (usedUnits + codePointUnits > contentLimit) {
                break;
            }
            result.appendCodePoint(codePoint);
            usedUnits += codePointUnits;
            offset += Character.charCount(codePoint);
        }
        return result.toString().stripTrailing() + "\u2026";
    }

    /// Counts ASCII as one display unit and wide Unicode text as two for deterministic narrow-row truncation.
    ///
    /// @param value normalized display text
    /// @return weighted display width
    private static int displayUnits(String value) {
        return value.codePoints().map(codePoint -> codePoint <= 0x7f ? 1 : 2).sum();
    }

    /// Groups the right-aligned status, fixed favorite button, and navigation arrow outside the title column.
    ///
    /// @param pluginId plugin ID controlled by the favorite button
    /// @param status current installed, update, or version label
    /// @param updateAvailable whether the newest compatible version is newer than the installed artifact
    /// @return stable trailing action group
    private HBox createPluginTrailingActions(
            String pluginId,
            String status,
            boolean updateAvailable
    ) {
        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMinWidth(Region.USE_PREF_SIZE);
        actions.setMinHeight(68);
        actions.setPrefHeight(68);

        Label statusLabel = new Label(status);
        statusLabel.getStyleClass().add("trailing-label");
        statusLabel.setAlignment(Pos.CENTER_RIGHT);
        statusLabel.setTextAlignment(TextAlignment.RIGHT);
        statusLabel.setWrapText(true);
        statusLabel.setMinWidth(104);
        statusLabel.setMaxWidth(140);
        if (updateAvailable) {
            statusLabel.setStyle("-fx-text-fill: -monet-primary;");
        }
        Node arrow = SVG.ARROW_FORWARD.createIcon(20);
        arrow.getStyleClass().add("trailing-icon");
        arrow.setMouseTransparent(true);
        actions.getChildren().addAll(statusLabel, createFavoriteButton(pluginId), arrow);
        return actions;
    }

    /// Returns a compact display label for a resolved compatible remote version.
    ///
    /// @param version resolved remote version
    /// @return localized version text
    private static String versionText(PluginStoreManifest.PluginVersionEntry version) {
        return "v" + version.getVersion();
    }

    /// Creates the familiar star affordance for a persisted favorite.
    ///
    /// @param pluginId plugin ID whose favorite state is controlled
    /// @return configured star button
    private JFXButton createFavoriteButton(String pluginId) {
        JFXButton button = new JFXButton();
        button.getStyleClass().add("jfx-tool-bar-button");
        button.setMinSize(36, 36);
        button.setPrefSize(36, 36);
        button.setMaxSize(36, 36);
        updateFavoriteButton(button, storePreferences.isFavorite(pluginId));
        button.setOnMouseClicked(event -> event.consume());
        button.setOnAction(event -> {
            boolean favorite = !storePreferences.isFavorite(pluginId);
            storePreferences.setFavorite(pluginId, favorite);
            updateFavoriteButton(button, storePreferences.isFavorite(pluginId));
            if (favoritesOnly.get() && !favorite) {
                applyFilter();
            }
        });
        return button;
    }

    /// Synchronizes star glyph, color, and tooltip with favorite state.
    ///
    /// @param button favorite button
    /// @param favorite current favorite state
    private static void updateFavoriteButton(JFXButton button, boolean favorite) {
        button.setText(favorite ? "\u2605" : "\u2606");
        button.setTooltip(new Tooltip(i18n(favorite
                ? "plugin.store.favorite.remove"
                : "plugin.store.favorite.add")));
        button.setAccessibleText(button.getTooltip().getText());
    }

    /// Shows all published versions and updates every detail section when selection changes.
    ///
    /// @param item resolved store item
    private void showPluginDetails(PluginStoreItem item) {
        @Nullable PluginStoreManifest manifest = item.getManifest();
        if (manifest == null) {
            showError(i18n("plugin.store.load_failed"), i18n("plugin.store.manifest_load_failed"));
            return;
        }
        PluginDetailsPage detailsPage = new PluginDetailsPage(item, manifest);
        activeDetailsPage = detailsPage;
        Controllers.navigate(detailsPage);
    }

    /// Returns a localized compatibility status and backend validation reason.
    ///
    /// @param sourceManager manager bound to the selected store item
    /// @param version selected remote version
    /// @return compatibility text
    private String compatibilityText(
            PluginStoreManager sourceManager,
            PluginStoreManifest.PluginVersionEntry version
    ) {
        if (version.getPluginApiVersion() != PluginManifest.CURRENT_SCHEMA_VERSION) {
            return i18n(
                    "plugin.store.compatibility.incompatible",
                    i18n(
                            "plugin.store.compatibility.unsupported_api",
                            PluginManifest.CURRENT_SCHEMA_VERSION,
                            version.getPluginApiVersion()
                    )
            );
        }
        try {
            sourceManager.validateCompatibility(version);
            List<String> requirements = new ArrayList<>();
            String launcherVersion = version.getLauncherVersion();
            if (!launcherVersion.isBlank() && !launcherVersion.equals("*")) {
                requirements.add(i18n("plugin.store.compatibility.launcher", launcherVersion));
            }
            if (!version.getRequiredJavaVersion().isBlank()) {
                requirements.add(i18n("plugin.store.compatibility.java", version.getRequiredJavaVersion()));
            }
            requirements.add(i18n("plugin.store.compatibility.plugin_api", version.getPluginApiVersion()));
            return i18n("plugin.store.compatibility.current") + " - " + String.join(", ", requirements);
        } catch (IOException exception) {
            return i18n("plugin.store.compatibility.incompatible", failureMessage(exception));
        }
    }

    /// Returns the complete launcher constraint for display without reducing it to a minimum version.
    ///
    /// @param launcherVersion normalized HMCL Nex version constraint
    /// @return full constraint or a localized unrestricted label
    static String launcherVersionRequirementText(String launcherVersion) {
        return launcherVersion.isBlank() || launcherVersion.equals("*")
                ? i18n("plugin.store.compatibility.any_launcher")
                : launcherVersion;
    }

    /// Selects the semantic icon for one version compatibility result.
    ///
    /// @param compatible whether the version can run on the current launcher
    /// @return success or error icon matching the compatibility state
    static SVG compatibilityIcon(boolean compatible) {
        return compatible ? SVG.CHECK_CIRCLE : SVG.ERROR;
    }

    /// Loads README text asynchronously and exposes loading, retry, unavailable, and error states.
    ///
    /// @param sourceManager manager bound to the repository manifest
    /// @param manifest repository manifest
    /// @param container README section content
    /// @param status status label retained across retries
    /// @param retryButton retry action
    private void loadReadme(
            PluginStoreManager sourceManager,
            PluginStoreManifest manifest,
            VBox container,
            Label status,
            JFXButton retryButton
    ) {
        container.getChildren().setAll(status, retryButton);
        status.setVisible(true);
        status.setManaged(true);
        retryButton.setVisible(false);
        retryButton.setManaged(false);
        if (manifest.getReadmeUrl().isBlank()) {
            status.setText(i18n("plugin.store.readme.unavailable"));
            return;
        }
        status.setText(i18n("plugin.store.readme.loading"));

        Task.supplyAsync(() -> {
            try {
                return sourceManager.fetchReadme(manifest);
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }).whenComplete(Schedulers.javafx(), (@Nullable var readme, @Nullable var exception) -> {
            if (exception != null) {
                status.setText(i18n("plugin.store.readme.failed", failureMessage(exception)));
                retryButton.setVisible(true);
                retryButton.setManaged(true);
                return;
            }
            if (readme.isBlank()) {
                status.setText(i18n("plugin.store.readme.unavailable"));
                return;
            }
            if (looksLikeHtml(readme)) {
                renderHtmlReadmeAsync(readme, manifest.getReadmeUrl(), container, status, retryButton);
            } else {
                status.setText("");
                status.setVisible(false);
                status.setManaged(false);
                TextArea text = new TextArea(readme);
                text.setEditable(false);
                text.setWrapText(true);
                text.setPrefRowCount(16);
                text.setMaxHeight(360);
                container.getChildren().setAll(text);
            }
        }).start();
    }

    /// Renders HTML README content off the JavaFX thread using the launcher's existing HTML renderer.
    ///
    /// @param html bounded README HTML
    /// @param baseUri README source URL used for relative links
    /// @param container README destination
    /// @param status reusable status label
    /// @param retryButton reusable retry action
    private void renderHtmlReadmeAsync(
            String html,
            String baseUri,
            VBox container,
            Label status,
            JFXButton retryButton
    ) {
        Task.supplyAsync(() -> renderHtmlReadme(html, baseUri))
                .whenComplete(Schedulers.javafx(), (@Nullable var rendered, @Nullable var exception) -> {
                    if (exception != null) {
                        status.setVisible(true);
                        status.setManaged(true);
                        status.setText(i18n("plugin.store.readme.failed", failureMessage(exception)));
                        retryButton.setVisible(true);
                        retryButton.setManaged(true);
                        container.getChildren().setAll(status, retryButton);
                        return;
                    }
                    container.getChildren().setAll(rendered);
                }).start();
    }

    /// Converts HTML into a JavaFX text flow with clickable links.
    ///
    /// @param html HTML source
    /// @param baseUri base URI for relative resources
    /// @return rendered JavaFX node
    private static Node renderHtmlReadme(String html, String baseUri) {
        Document document = parseSafeReadmeHtml(html, baseUri);
        HTMLRenderer renderer = new HTMLRenderer(uri -> {
            if (isSafeExternalLink(uri.toString())) {
                FXUtils.openLink(uri.toString());
            }
        });
        for (org.jsoup.nodes.Node node : document.body().childNodes()) {
            renderer.appendNode(node);
        }
        renderer.mergeLineBreaks();
        return renderer.render();
    }

    /// Parses repository HTML while removing elements that could automatically load remote resources.
    ///
    /// Hyperlinks remain available as explicit user actions, but images, embedded documents, media, scripts,
    /// and styles are excluded so README rendering cannot bypass the plugin-store transport policy.
    ///
    /// @param html bounded README HTML
    /// @param baseUri README source URL used to resolve user-clicked links
    /// @return sanitized HTML document
    static Document parseSafeReadmeHtml(String html, String baseUri) {
        Document document = Jsoup.parse(html, baseUri);
        document.select("img, picture, source, video, audio, iframe, object, embed, link, script, style").remove();
        for (Element link : document.select("a[href]")) {
            String resolved = link.absUrl("href");
            if (isSafeExternalLink(resolved)) {
                link.attr("href", resolved);
            } else {
                link.removeAttr("href");
            }
        }
        return document;
    }

    /// Returns whether an explicit store hyperlink uses HTTPS or loopback-only development HTTP.
    ///
    /// @param url resolved hyperlink URL or `null`
    /// @return whether the link may be handed to the operating-system browser
    static boolean isSafeExternalLink(@Nullable String url) {
        if (StringUtils.isBlank(url)) {
            return false;
        }
        try {
            URI uri = new URI(Objects.requireNonNull(url));
            @Nullable String host = uri.getHost();
            if (host == null) {
                return false;
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return true;
            }
            return "http".equalsIgnoreCase(uri.getScheme()) && isLoopbackHost(host);
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    /// Returns whether a host denotes the local machine for development-only HTTP links.
    ///
    /// @param host parsed URI host
    /// @return whether the host is loopback
    private static boolean isLoopbackHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || "::1".equals(normalized)
                || "[::1]".equals(normalized)
                || normalized.startsWith("127.");
    }

    /// Detects content that is already HTML instead of Markdown or plain text.
    ///
    /// @param content README content
    /// @return whether an HTML renderer should be used
    private static boolean looksLikeHtml(String content) {
        return content.matches("(?is).*<(html|body|h[1-6]|p|div|ul|ol|li|table|pre|blockquote|a|img|br)(\\s|>|/).*?");
    }

    /// Resolves a complete dependency plan for the explicitly selected version before confirmation.
    ///
    /// @param item selected store item
    /// @param version explicitly selected version
    private void resolveAndConfirmInstall(
            PluginStoreItem item,
            PluginStoreManifest.PluginVersionEntry version
    ) {
        PluginDialogs.ProgressDialog resolvingDialog = PluginDialogs.showProgress(
                i18n("plugin.store.installing"),
                i18n("plugin.store.install.resolving")
        );

        Task.supplyAsync(() -> {
            try {
                PluginInstallationPlanningSnapshot planningSnapshot =
                        pluginManager.getInstallationPlanningSnapshot();
                return item.getSourceManager().resolveInstallPlan(
                        item.getEntry().getId(),
                        version,
                        planningSnapshot.getManifests(),
                        planningSnapshot.getInstalledArtifacts(),
                        planningSnapshot.getReusableArtifacts()
                );
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }).whenComplete(Schedulers.javafx(), (@Nullable var plan, @Nullable var exception) -> {
            resolvingDialog.close();
            if (exception != null) {
                showError(
                        i18n("plugin.store.install.conflict"),
                        i18n("plugin.store.install.plan_failed", failureMessage(exception))
                );
                return;
            }
            confirmInstallPlan(plan, item.getSourceManager());
        }).start();
    }

    /// Shows one declaration-limited permission grant group for every package that will change.
    ///
    /// Updates always require a fresh confirmation. Existing grants that remain declared are preselected,
    /// while permissions newly requested by the selected version start denied. Reused dependencies are shown
    /// in the plan but cannot modify permission state.
    ///
    /// @param plan dependency-first resolved plan
    /// @param sourceManager manager bound to every downloaded plan entry
    private void confirmInstallPlan(PluginInstallPlan plan, PluginStoreManager sourceManager) {
        PluginInstallPlan.Entry root = plan.getRootEntry();
        List<PluginPermissionRequest> requests = new ArrayList<>();
        try {
            for (PluginInstallPlan.Entry entry : plan.getPermissionReviewEntries()) {
                @Unmodifiable Set<PluginPermission> previousGrants =
                        entry.getAction() == PluginInstallPlan.Action.UPDATE
                                ? pluginManager.getGrantedPermissions(entry.getPluginId())
                                : Set.of();
                @Nullable PluginManifest installedManifest = entry.getInstalledManifest();
                @Unmodifiable List<PluginPermission> previousRequiredPermissions =
                        installedManifest == null ? List.of() : installedManifest.getRequiredPermissions();
                @Unmodifiable List<PluginPermission> previousOptionalPermissions =
                        installedManifest == null ? List.of() : installedManifest.getOptionalPermissions();
                requests.add(createPermissionReviewRequest(
                        entry.getPluginId(),
                        entry.getDisplayName(),
                        entry.getVersion(),
                        entry.getAction(),
                        entry.getRequiredPermissions(),
                        entry.getOptionalPermissions(),
                        previousGrants,
                        previousRequiredPermissions,
                        previousOptionalPermissions
                ));
            }
        } catch (IOException exception) {
            showError(i18n("plugin.store.install_failed"), failureMessage(exception));
            return;
        }

        PluginDialogs.confirmPluginInstall(
                root.getDisplayName(),
                root.getAction() == PluginInstallPlan.Action.UPDATE,
                List.copyOf(requests),
                formatInstallPlan(plan),
                grantsByPluginId -> executeInstallPlan(plan, sourceManager, grantsByPluginId)
        );
    }

    /// Returns the root action produced by dependency planning for the current future installed state.
    ///
    /// Pending-uninstall artifacts are absent from the planning snapshot, so reinstalling one is presented as a
    /// fresh installation just as [PluginStoreManager#resolveInstallPlan] will classify it.
    ///
    /// @param planningManifest future installed manifest or `null` when the root will be newly installed
    /// @return install or update action used by both the details button and confirmation workflow
    static PluginInstallPlan.Action rootInstallationAction(@Nullable PluginManifest planningManifest) {
        return planningManifest == null
                ? PluginInstallPlan.Action.INSTALL
                : PluginInstallPlan.Action.UPDATE;
    }

    /// Calculates the preselected grants for one fresh installation review.
    ///
    /// Required permissions are always selected. New installations leave every optional permission denied, while
    /// updates preserve only optional permissions that were previously granted and remain optional in the target.
    ///
    /// @param action resolved plan action
    /// @param requiredPermissions permissions required by the selected version
    /// @param optionalPermissions optional permissions requested by the selected version
    /// @param previousGrants grants stored for the installed version
    /// @return immutable required permissions plus inherited optional grants
    static @Unmodifiable Set<PluginPermission> initialPermissionsFor(
            PluginInstallPlan.Action action,
            List<PluginPermission> requiredPermissions,
            List<PluginPermission> optionalPermissions,
            Set<PluginPermission> previousGrants
    ) {
        Set<PluginPermission> initiallyGranted = new LinkedHashSet<>(requiredPermissions);
        if (action == PluginInstallPlan.Action.UPDATE) {
            for (PluginPermission permission : optionalPermissions) {
                if (previousGrants.contains(permission)) {
                    initiallyGranted.add(permission);
                }
            }
        }
        return Set.copyOf(initiallyGranted);
    }

    /// Creates one editable permission request for an artifact that will be installed or updated.
    ///
    /// Updates always produce a request, including when their declaration is unchanged or empty. Reused artifacts
    /// cannot produce a request because their package and permission decision remain unchanged.
    ///
    /// @param pluginId stable plugin ID
    /// @param displayName human-readable plugin name
    /// @param version exact target version
    /// @param action resolved install-plan action
    /// @param requiredPermissions permissions required by the target artifact
    /// @param optionalPermissions optional permissions requested by the target artifact
    /// @param previousGrants grants belonging to the currently installed artifact
    /// @param previousRequiredPermissions permissions required by the installed artifact
    /// @param previousOptionalPermissions optional permissions declared by the installed artifact
    /// @return editable declaration-limited permission review request
    /// @throws IllegalArgumentException if the artifact is reused rather than changed
    static PluginPermissionRequest createPermissionReviewRequest(
            String pluginId,
            String displayName,
            String version,
            PluginInstallPlan.Action action,
            List<PluginPermission> requiredPermissions,
            List<PluginPermission> optionalPermissions,
            Set<PluginPermission> previousGrants,
            List<PluginPermission> previousRequiredPermissions,
            List<PluginPermission> previousOptionalPermissions
    ) {
        if (action == PluginInstallPlan.Action.REUSE) {
            throw new IllegalArgumentException("Reused plugin artifacts do not require permission review");
        }
        return new PluginPermissionRequest(
                pluginId,
                displayName,
                version,
                requiredPermissions,
                optionalPermissions,
                initialPermissionsFor(action, requiredPermissions, optionalPermissions, previousGrants),
                true,
                action == PluginInstallPlan.Action.UPDATE,
                previousRequiredPermissions,
                previousOptionalPermissions
        );
    }

    /// Formats actions and dependency constraints for every plan entry in topological order.
    ///
    /// @param plan resolved plan
    /// @return immutable plan rows
    private static @Unmodifiable List<String> formatInstallPlan(PluginInstallPlan plan) {
        List<String> rows = new ArrayList<>();
        for (PluginInstallPlan.Entry entry : plan.getEntries()) {
            switch (entry.getAction()) {
                case REUSE -> rows.add(i18n(
                        "plugin.store.plan.keep",
                        entry.getDisplayName(),
                        entry.getVersion()
                ));
                case INSTALL -> rows.add(i18n(
                        "plugin.store.plan.install",
                        entry.getDisplayName(),
                        entry.getVersion()
                ));
                case UPDATE -> {
                    @Nullable PluginManifest installed = entry.getInstalledManifest();
                    rows.add(i18n(
                            "plugin.store.plan.update",
                            entry.getDisplayName(),
                            installed == null ? "?" : installed.getVersion(),
                            entry.getVersion()
                    ));
                }
            }
            for (PluginDependency dependency : entry.getDependencies()) {
                rows.add(i18n(
                        "plugin.store.plan.dependency",
                        entry.getDisplayName(),
                        dependency.getId(),
                        dependency.getVersion()
                ));
            }
        }
        return List.copyOf(rows);
    }

    /// Downloads every mutable plan entry into isolated staging before any installed file is touched.
    ///
    /// @param plan confirmed installation plan
    /// @param sourceManager manager bound to the resolved dependency plan
    /// @param grantsByPluginId immutable grants chosen for every changed plugin
    private void executeInstallPlan(
            PluginInstallPlan plan,
            PluginStoreManager sourceManager,
            @Unmodifiable Map<String, @Unmodifiable Set<PluginPermission>> grantsByPluginId
    ) {
        PluginDialogs.ProgressDialog progressDialog = PluginDialogs.showProgress(
                i18n("plugin.store.installing"),
                i18n("plugin.store.install.resolving")
        );

        Task.supplyAsync(() -> {
            @Nullable Path stagingDirectory = null;
            try {
                stagingDirectory = Files.createTempDirectory("hmcl-plugin-plan-");
                Map<String, Path> stagedPackages = new LinkedHashMap<>();
                @Unmodifiable List<PluginInstallPlan.Entry> downloads = plan.getDownloadEntries();
                for (int index = 0; index < downloads.size(); index++) {
                    PluginInstallPlan.Entry entry = downloads.get(index);
                    int current = index + 1;
                    progressDialog.setStatus(i18n(
                            "plugin.store.install.downloading_package",
                            entry.getDisplayName(),
                            current,
                            downloads.size()
                    ));
                    PluginStoreManifest.PluginVersionEntry remoteVersion =
                            Objects.requireNonNull(entry.getRemoteVersion(), "Download entry has no remote version");
                    Path staged = sourceManager.downloadPluginToStaging(
                            entry.getPluginId(),
                            remoteVersion,
                            stagingDirectory
                    );
                    stagedPackages.put(entry.getPluginId(), staged);
                }
                return new InstallOperation(plan, stagingDirectory, stagedPackages, grantsByPluginId);
            } catch (IOException | RuntimeException exception) {
                if (stagingDirectory != null) {
                    cleanupStaging(stagingDirectory);
                }
                throw new RuntimeException(exception);
            }
        }).whenComplete(Schedulers.javafx(), (@Nullable var operation, @Nullable var exception) -> {
            if (exception != null) {
                progressDialog.close();
                showError(i18n("plugin.store.install_failed"), failureMessage(exception));
                return;
            }
            progressDialog.setStatus(i18n("plugin.store.install.download_complete"));
            publishRestartPlan(operation, progressDialog);
        }).start();
    }

    /// Inspects every staged package, then atomically publishes the complete restart installation in one manager call.
    ///
    /// The inspection list retains dependency-first plan order. [PluginManager#stagePluginInstallations] validates
    /// the complete future graph and rolls back publication as one transaction, so no package is loaded against an
    /// old in-memory dependency and no intermediate replacement graph is exposed.
    ///
    /// @param operation staged install operation
    /// @param progressDialog shared progress dialog
    private void publishRestartPlan(
            InstallOperation operation,
            PluginDialogs.ProgressDialog progressDialog
    ) {
        Task.runAsync(() -> {
            try {
                @Unmodifiable List<PluginInstallPlan.Entry> downloads = operation.plan.getDownloadEntries();
                List<LocalPluginInspection> inspections = new ArrayList<>();
                for (int index = 0; index < downloads.size(); index++) {
                    PluginInstallPlan.Entry entry = downloads.get(index);
                    int current = index + 1;
                    progressDialog.setStatus(i18n(
                            "plugin.store.install.publishing_package",
                            entry.getDisplayName(),
                            current,
                            downloads.size()
                    ));
                    Path stagedPackage = Objects.requireNonNull(
                            operation.stagedPackages.get(entry.getPluginId()),
                            "No staged package for " + entry.getPluginId()
                    );
                    inspections.add(pluginManager.inspectLocalPluginPackage(stagedPackage));
                }
                pluginManager.stagePluginInstallations(
                        List.copyOf(inspections),
                        operation.grantsByPluginId,
                        operation.plan.getReusableArtifactIdentities(),
                        operation.plan.getExpectedPriorArtifacts()
                );
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }).whenComplete(Schedulers.javafx(), (@Nullable var result, @Nullable var exception) -> {
            if (exception != null) {
                cleanupStaging(operation.stagingDirectory);
                progressDialog.close();
                showError(i18n("plugin.store.install_failed"), failureMessage(exception));
                return;
            }
            cleanupStaging(operation.stagingDirectory);
            progressDialog.close();
            refreshInstalledManifests();
            applyFilter();
            @Nullable PluginDetailsPage detailsPage = activeDetailsPage;
            if (detailsPage != null) {
                detailsPage.refreshLocalInstallationState();
            }
            PluginDialogs.showInstallFinished(
                    operation.plan.getRootEntry().getDisplayName(),
                    true
            );
        }).start();
    }

    /// Removes an isolated staging directory and logs cleanup failures without masking the primary result.
    ///
    /// @param stagingDirectory staging directory
    private static void cleanupStaging(Path stagingDirectory) {
        try {
            FileUtils.deleteDirectory(stagingDirectory);
        } catch (IOException exception) {
            LOG.warning("Failed to clean plugin installation staging directory: " + stagingDirectory, exception);
        }
    }

    /// Formats a byte count using compact binary units.
    ///
    /// @param size byte count
    /// @return formatted size
    private static String formatSize(long size) {
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", size / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", size / 1024.0 / 1024.0);
    }

    /// Shows an application-owned HMCL error dialog on the JavaFX thread.
    ///
    /// @param title localized title
    /// @param message failure detail
    private static void showError(String title, String message) {
        PluginDialogs.showError(title, message);
    }

    /// Unwraps nested asynchronous exceptions and always returns usable diagnostic text.
    ///
    /// @param exception failure to unwrap
    /// @return root-cause message
    private static String failureMessage(Throwable exception) {
        Throwable cause = exception;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        @Nullable String message = cause.getMessage();
        return StringUtils.isBlank(message) ? cause.toString() : Objects.requireNonNull(message);
    }

    /// Returns the decorator navigation state.
    ///
    /// @return read-only page state
    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /// Starts the first registry request when this lazily-created settings tab becomes visible.
    @Override
    public void onPageShown() {
        if (!initialLoadRequested) {
            initialLoadRequested = true;
            loadPluginStore();
            return;
        }
        refreshInstalledManifests();
        applyFilter();
    }

    /// Reloads the selected store source after an explicit refresh request.
    @Override
    public void refresh() {
        loadPluginStore();
    }

    /// HMCL navigation page displaying every detail of one remote plugin and its selected version.
    @NotNullByDefault
    private final class PluginDetailsPage extends VBox implements DecoratorPage {
        /// Decorator navigation state for the selected plugin.
        private final ReadOnlyObjectWrapper<State> detailsState;

        /// Store item whose metadata and versions are displayed.
        private final PluginStoreItem item;

        /// Repository manifest containing versions and README metadata.
        private final PluginStoreManifest manifest;

        /// Source-scoped manager retained with the selected store item.
        private final PluginStoreManager sourceManager;

        /// Native HMCL selector for the exact version displayed and installed.
        private final LineSelectButton<VersionItem> versionSelector = new LineSelectButton<>();

        /// Compatibility detail row updated with the selected version.
        private final LineButton compatibilityRow = createReadOnlyRow(
                SVG.CHECK_CIRCLE,
                i18n("plugin.store.compatibility"),
                null
        );

        /// Complete HMCL Nex version constraint declared by the selected package version.
        private final LineButton launcherVersionRow = createReadOnlyRow(
                SVG.SCHEMA,
                i18n("plugin.store.launcher_version"),
                null
        );

        /// Release channel detail row updated with the selected version.
        private final LineButton channelRow = createReadOnlyRow(
                SVG.RELEASE_CIRCLE,
                i18n("plugin.store.channel"),
                null
        );

        /// Release date detail row updated with the selected version.
        private final LineButton releaseDateRow = createReadOnlyRow(
                SVG.UPDATE,
                i18n("plugin.store.release_date"),
                null
        );

        /// Package size detail row updated with the selected version.
        private final LineButton sizeRow = createReadOnlyRow(
                SVG.ARCHIVE,
                i18n("plugin.store.size"),
                null
        );

        /// Restart requirement detail row updated with the selected version.
        private final LineButton restartRow = createReadOnlyRow(
                SVG.REFRESH,
                i18n("plugin.store.requires_restart"),
                null
        );

        /// Section that is replaced with the selected version's declaration-only permission pane.
        private final VBox permissionSection = new VBox();

        /// Native dependency rows for the selected version.
        private final ComponentList dependencyList = new ComponentList();

        /// Native changelog row for the selected version.
        private final LineButton changelogRow = createReadOnlyRow(
                SVG.CHAT,
                i18n("plugin.store.changelog"),
                null
        );

        /// Installs the exact selected compatible version.
        private final JFXButton installButton;

        /// Identity and installed-version rows rebuilt after local plugin state changes.
        private final ComponentList identityList = new ComponentList();

        /// Creates a scrollable HMCL settings-style details page.
        ///
        /// @param item selected store item
        /// @param manifest resolved repository manifest
        private PluginDetailsPage(PluginStoreItem item, PluginStoreManifest manifest) {
            this.item = item;
            this.manifest = manifest;
            this.sourceManager = item.getSourceManager();
            this.detailsState = new ReadOnlyObjectWrapper<>(State.fromTitle(displayName(item)));
            getStyleClass().add("gray-background");

            VBox content = new VBox(10);
            content.setPadding(new Insets(10));
            content.setFillWidth(true);

            HBox actions = new HBox(8);
            actions.setAlignment(Pos.CENTER_RIGHT);
            actions.setMinHeight(56);
            actions.setPadding(new Insets(8, 10, 10, 10));
            actions.getChildren().add(createFavoriteButton(item.getEntry().getId()));
            if (isSafeExternalLink(item.getEntry().getRepository())) {
                actions.getChildren().add(createIconButton(
                        i18n("plugin.store.open_repository"),
                        SVG.OPEN_IN_NEW,
                        () -> FXUtils.openLink(item.getEntry().getRepository())
                ));
            }
            installButton = createToolbarButton2(
                    i18n("plugin.store.install.selected"),
                    SVG.DOWNLOAD,
                    this::installSelectedVersion
            );
            installButton.getStyleClass().add("jfx-button-raised");
            installButton.setMinHeight(40);
            actions.getChildren().add(installButton);

            content.getChildren().add(ComponentList.createComponentListTitle(i18n("plugin.store.details")));
            populateIdentityList();
            content.getChildren().add(identityList);

            versionSelector.setLeading(SVG.UPDATE);
            versionSelector.setTitle(i18n("plugin.store.version"));
            versionSelector.setNullSafeConverter(VersionItem::toString);
            versionSelector.setDescriptionConverter((@Nullable var candidate) -> candidate == null
                    ? ""
                    : compatibilityText(sourceManager, candidate.version));
            @Unmodifiable List<VersionItem> versions = manifest.getVersionsNewestFirst().stream()
                    .map(version -> new VersionItem(version, sourceManager.isCompatible(version)))
                    .toList();
            versionSelector.setItems(versions);
            versionSelector.valueProperty().addListener((var observable, @Nullable var oldValue,
                    @Nullable var selected) -> {
                if (selected != null) {
                    updateSelectedVersion(selected);
                }
            });

            ComponentList versionList = new ComponentList();
            versionList.getContent().add(versionSelector);
            content.getChildren().addAll(
                    ComponentList.createComponentListTitle(i18n("plugin.store.version")),
                    versionList
            );

            ComponentList metadataList = new ComponentList();
            metadataList.getContent().addAll(
                    compatibilityRow,
                    launcherVersionRow,
                    channelRow,
                    releaseDateRow,
                    sizeRow,
                    restartRow
            );
            content.getChildren().addAll(
                    ComponentList.createComponentListTitle(i18n("plugin.store.compatibility")),
                    metadataList
            );

            content.getChildren().add(permissionSection);
            dependencyList.getStyleClass().add("no-padding");
            content.getChildren().addAll(
                    ComponentList.createComponentListTitle(i18n("plugin.store.dependencies")),
                    dependencyList,
                    ComponentList.createComponentListTitle(i18n("plugin.store.changelog"))
            );
            ComponentList changelogList = new ComponentList();
            changelogList.getContent().add(changelogRow);
            content.getChildren().add(changelogList);

            VBox readmeContainer = new VBox(8);
            Label readmeStatus = new Label();
            readmeStatus.setWrapText(true);
            JFXButton retryButton = new JFXButton(i18n("plugin.store.readme.retry"));
            retryButton.setOnAction(event ->
                    loadReadme(sourceManager, manifest, readmeContainer, readmeStatus, retryButton));
            content.getChildren().addAll(
                    ComponentList.createComponentListTitle(i18n("plugin.store.readme")),
                    readmeContainer
            );

            ScrollPane scrollPane = new ScrollPane(content);
            scrollPane.setFitToWidth(true);
            scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            VBox.setVgrow(scrollPane, Priority.ALWAYS);
            FXUtils.smoothScrolling(scrollPane);
            FXUtils.setOverflowHidden(scrollPane, 8);
            getChildren().setAll(scrollPane, actions);

            @Nullable PluginStoreManifest.PluginVersionEntry latestCompatible =
                    sourceManager.getLatestCompatibleVersion(manifest);
            @Nullable VersionItem initial = versions.stream()
                    .filter(candidate -> latestCompatible != null
                            && candidate.version.getVersion().equals(latestCompatible.getVersion()))
                    .findFirst()
                    .orElse(versions.isEmpty() ? null : versions.get(0));
            if (initial == null) {
                installButton.setDisable(true);
            } else {
                versionSelector.setValue(initial);
            }
            loadReadme(sourceManager, manifest, readmeContainer, readmeStatus, retryButton);
        }

        /// Builds the plugin identity, author, category, tags, installed version, and license rows.
        ///
        private void populateIdentityList() {
            PluginStoreRegistry.PluginStoreEntry entry = item.getEntry();
            identityList.getContent().clear();

            LineButton identity = createReadOnlyRow(
                    SVG.EXTENSION,
                    displayName(item),
                    StringUtils.isBlank(entry.getDescription()) ? null : entry.getDescription()
            );
            identity.setLargeTitle(true);
            identityList.getContent().add(identity);
            identityList.getContent().add(createReadOnlyRow(
                    SVG.INFO,
                    "ID",
                    entry.getId()
            ));

            if (StringUtils.isNotBlank(entry.getAuthor())) {
                identityList.getContent().add(createReadOnlyRow(
                        SVG.PERSON,
                        i18n("plugin.author"),
                        entry.getAuthor()
                ));
            }
            if (StringUtils.isNotBlank(entry.getCategory())) {
                identityList.getContent().add(createReadOnlyRow(
                        SVG.LIST,
                        i18n("plugin.store.category"),
                        entry.getCategory()
                ));
            }
            @Unmodifiable List<String> tags = entry.getTags().stream()
                    .filter(StringUtils::isNotBlank)
                    .toList();
            if (!tags.isEmpty()) {
                identityList.getContent().add(createReadOnlyRow(
                        SVG.LISTS,
                        i18n("plugin.store.tags"),
                        String.join(", ", tags)
                ));
            }
            @Nullable PluginManifest installed = installedManifests.get(entry.getId());
            if (installed != null) {
                identityList.getContent().add(createReadOnlyRow(
                        SVG.CHECK_CIRCLE,
                        i18n("plugin.store.installed_version"),
                        installed.getVersion()
                ));
            }
            if (StringUtils.isNotBlank(manifest.getLicense())) {
                identityList.getContent().add(createReadOnlyRow(
                        SVG.SCRIPT,
                        i18n("plugin.store.license"),
                        manifest.getLicense()
                ));
            }
        }

        /// Refreshes installed metadata and the selected version's action after a local state publication.
        private void refreshLocalInstallationState() {
            populateIdentityList();
            @Nullable VersionItem selected = versionSelector.getValue();
            if (selected != null) {
                updateSelectedVersion(selected);
            }
        }

        /// Refreshes all version-specific metadata and declaration-only lists.
        ///
        /// @param selected selected version wrapper
        private void updateSelectedVersion(VersionItem selected) {
            PluginStoreManifest.PluginVersionEntry version = selected.version;
            compatibilityRow.setLeading(compatibilityIcon(selected.compatible));
            compatibilityRow.pseudoClassStateChanged(PSEUDO_ERROR, !selected.compatible);
            compatibilityRow.setSubtitle(compatibilityText(sourceManager, version));
            String launcherVersion = version.getLauncherVersion();
            launcherVersionRow.setSubtitle(launcherVersionRequirementText(launcherVersion));
            channelRow.setSubtitle(version.getChannel());
            releaseDateRow.setSubtitle(version.getReleaseDate().isBlank() ? "-" : version.getReleaseDate());
            @Nullable Long size = version.getSize();
            sizeRow.setSubtitle(size == null ? "-" : formatSize(size));
            restartRow.setSubtitle(i18n(version.isRequiresRestart()
                    ? "plugin.store.requires_restart.yes"
                    : "plugin.store.requires_restart.no"));
            changelogRow.setSubtitle(version.getReleaseNotes().isBlank()
                    ? i18n("plugin.store.changelog.empty")
                    : version.getReleaseNotes());

            permissionSection.getChildren().setAll(
                    ComponentList.createComponentListTitle(i18n("plugin.store.permissions")),
                    new PluginPermissionPane(
                            version.getRequiredPermissions(),
                            version.getOptionalPermissions(),
                            Set.of(),
                            false,
                            Set.of(),
                            Set.of()
                    )
            );
            populateDependencyRows(version.getDependencies());
            @Nullable String installedFailure = installedManifestFailure;
            if (installedFailure != null) {
                installButton.setTooltip(new Tooltip(
                        i18n("plugin.installed.load_failed") + ": " + installedFailure
                ));
                installButton.setDisable(true);
                return;
            }
            @Nullable PluginManifest planningManifest =
                    installPlanningManifests.get(item.getEntry().getId());
            PluginInstallPlan.Action action = rootInstallationAction(planningManifest);
            installButton.setText(i18n(action == PluginInstallPlan.Action.UPDATE
                    ? "plugin.store.update"
                    : "plugin.store.install.selected"));
            installButton.setTooltip(selected.compatible
                    ? null
                    : new Tooltip(i18n("plugin.store.install.incompatible", compatibilityText(sourceManager, version))));
            installButton.setDisable(!selected.compatible);
        }

        /// Rebuilds dependency rows for the currently selected version.
        ///
        /// @param dependencies developer-declared plugin dependencies
        private void populateDependencyRows(List<PluginDependency> dependencies) {
            dependencyList.getContent().clear();
            if (dependencies.isEmpty()) {
                dependencyList.getContent().add(createReadOnlyRow(
                        SVG.INFO,
                        i18n("plugin.store.dependencies.none"),
                        null
                ));
                return;
            }
            for (PluginDependency dependency : dependencies) {
                dependencyList.getContent().add(createReadOnlyRow(
                        SVG.SCHEMA,
                        dependency.getId(),
                        dependency.getVersion()
                ));
            }
        }

        /// Starts dependency resolution for the exact version selected on this page.
        private void installSelectedVersion() {
            @Nullable VersionItem selected = versionSelector.getValue();
            if (selected != null && selected.compatible) {
                resolveAndConfirmInstall(item, selected.version);
            }
        }

        /// Returns the decorator navigation state for this detail page.
        ///
        /// @return read-only page state
        @Override
        public ReadOnlyObjectProperty<State> stateProperty() {
            return detailsState.getReadOnlyProperty();
        }
    }

    /// Creates a non-interactive native row for metadata, changelog, and empty states.
    ///
    /// @param icon leading icon
    /// @param title row title
    /// @param subtitle optional wrapped detail
    /// @return configured read-only row
    private static LineButton createReadOnlyRow(SVG icon, String title, @Nullable String subtitle) {
        LineButton row = new LineButton();
        row.setLeading(icon);
        row.setTitle(title);
        row.setSubtitle(subtitle);
        row.setMouseTransparent(true);
        return row;
    }

    /// Immutable result of one isolated registry load request.
    @NotNullByDefault
    private static final class StoreLoadResult {
        /// Manager containing the exact registry and caches produced by this request.
        private final PluginStoreManager manager;

        /// Resolved store items belonging to the same manager and registry.
        private final @Unmodifiable List<PluginStoreItem> items;

        /// Creates an atomic manager-and-items result for generation-checked publication.
        ///
        /// @param manager isolated manager that completed the request
        /// @param items resolved registry items
        private StoreLoadResult(PluginStoreManager manager, List<PluginStoreItem> items) {
            this.manager = manager;
            this.items = List.copyOf(items);
        }
    }

    /// One category selector value with a stable filter key and localized display label.
    @NotNullByDefault
    private static final class CategoryItem {
        /// Stable category value used by filtering.
        private final String value;

        /// Localized or registry-provided display label.
        private final String label;

        /// Creates one category option.
        ///
        /// @param value stable filter value
        /// @param label display label
        private CategoryItem(String value, String label) {
            this.value = value;
            this.label = label;
        }

        /// Returns the selector display label.
        ///
        /// @return category label
        @Override
        public String toString() {
            return label;
        }
    }

    /// One version selector row carrying exact metadata and compatibility state.
    @NotNullByDefault
    private static final class VersionItem {
        /// Exact published version selected for details or installation.
        private final PluginStoreManifest.PluginVersionEntry version;

        /// Whether the current launcher and Java runtime can install this version.
        private final boolean compatible;

        /// Creates a version selector row.
        ///
        /// @param version exact version metadata
        /// @param compatible current runtime compatibility
        private VersionItem(PluginStoreManifest.PluginVersionEntry version, boolean compatible) {
            this.version = version;
            this.compatible = compatible;
        }

        /// Returns version, channel, and incompatibility marker for the selector.
        ///
        /// @return selector label
        @Override
        public String toString() {
            String label = "v" + version.getVersion() + " - " + version.getChannel();
            return compatible ? label : label + " - " + i18n("plugin.store.version.incompatible");
        }
    }

    /// Mutable orchestration state retained across asynchronous per-entry publication.
    @NotNullByDefault
    private static final class InstallOperation {
        /// Confirmed dependency-first plan.
        private final PluginInstallPlan plan;

        /// Isolated directory containing every verified download.
        private final Path stagingDirectory;

        /// Staged package path indexed by plugin ID.
        private final @Unmodifiable Map<String, Path> stagedPackages;

        /// Confirmed declaration-limited grants indexed by changed plugin ID.
        private final @Unmodifiable Map<String, @Unmodifiable Set<PluginPermission>> grantsByPluginId;

        /// Creates an operation after every required package has downloaded successfully.
        ///
        /// @param plan confirmed plan
        /// @param stagingDirectory isolated staging directory
        /// @param stagedPackages staged packages by plugin ID
        /// @param grantsByPluginId confirmed permission grants by changed plugin ID
        private InstallOperation(
                PluginInstallPlan plan,
                Path stagingDirectory,
                Map<String, Path> stagedPackages,
                Map<String, @Unmodifiable Set<PluginPermission>> grantsByPluginId
        ) {
            this.plan = plan;
            this.stagingDirectory = stagingDirectory;
            this.stagedPackages = Map.copyOf(stagedPackages);
            this.grantsByPluginId = Map.copyOf(grantsByPluginId);
        }
    }
}

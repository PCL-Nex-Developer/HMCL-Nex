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
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.plugin.PluginContainer;
import org.jackhuang.hmcl.plugin.PluginManager;
import org.jackhuang.hmcl.plugin.store.PluginStoreItem;
import org.jackhuang.hmcl.plugin.store.PluginStoreManager;
import org.jackhuang.hmcl.plugin.store.PluginStoreManifest;
import org.jackhuang.hmcl.plugin.store.PluginStoreRegistry;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.util.StringUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public class PluginStorePage extends VBox implements DecoratorPage {
    private static final String ALL_CATEGORIES = "__all__";

    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("plugin.store")));
    private final PluginStoreManager storeManager = new PluginStoreManager();
    private final PluginManager pluginManager = PluginManager.getInstance();
    private final ComponentList pluginList = new ComponentList();
    private final JFXSpinner loadingSpinner = new JFXSpinner();
    private final Label statusLabel = new Label();
    private final JFXTextField searchField = new JFXTextField();
    private final JFXComboBox<CategoryItem> categoryBox = new JFXComboBox<>();
    private final JFXComboBox<String> sourceBox = new JFXComboBox<>();
    private final List<PluginStoreItem> allItems = new ArrayList<>();
    private boolean initialized = false;

    public PluginStorePage() {
        setSpacing(10);
        setPadding(new Insets(10));

        HBox topBar = new HBox(10);
        topBar.setAlignment(Pos.CENTER_LEFT);

        searchField.setPromptText(i18n("plugin.store.search"));
        searchField.setPrefWidth(260);
        searchField.textProperty().addListener((a, b, c) -> applyFilter());

        categoryBox.setPrefWidth(150);
        categoryBox.valueProperty().addListener((a, b, c) -> applyFilter());

        sourceBox.setPrefWidth(360);
        sourceBox.getItems().setAll(storeManager.getRegistryUrls());
        sourceBox.getSelectionModel().select(storeManager.getRegistryUrl());

        JFXButton refreshButton = new JFXButton(i18n("plugin.store.refresh"));
        refreshButton.getStyleClass().add("jfx-button-raised");
        refreshButton.setOnAction(e -> loadPluginStore());

        JFXButton settingsButton = new JFXButton(i18n("plugin.store.settings"));
        settingsButton.setOnAction(e -> showStoreSettings());

        topBar.getChildren().addAll(searchField, categoryBox, sourceBox, refreshButton, settingsButton);

        StackPane loadingPane = new StackPane(loadingSpinner);
        loadingPane.setAlignment(Pos.CENTER);
        loadingPane.setPadding(new Insets(10));
        loadingSpinner.setVisible(false);

        statusLabel.setStyle("-fx-padding: 10; -fx-text-fill: gray;");

        ScrollPane scrollPane = new ScrollPane(pluginList);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        getChildren().addAll(topBar, loadingPane, statusLabel, scrollPane);
        // Don't load store in constructor - wait until page is actually shown
    }

    private void loadPluginStore() {
        loadingSpinner.setVisible(true);
        statusLabel.setText(i18n("plugin.store.loading"));
        pluginList.getContent().clear();
        allItems.clear();
        storeManager.clearCache();

        String registryUrl = sourceBox.getSelectionModel().getSelectedItem();
        if (StringUtils.isBlank(registryUrl)) {
            registryUrl = storeManager.getRegistryUrl();
        }
        String finalRegistryUrl = registryUrl;

        Task.runAsync(() -> {
            try {
                storeManager.setActiveRegistryUrl(finalRegistryUrl);
                allItems.addAll(storeManager.getStoreItems());
            } catch (IOException e) {
                LOG.error("Failed to load plugin store", e);
                throw new RuntimeException(e);
            }
        }).whenComplete(Schedulers.javafx(), (result, exception) -> {
            loadingSpinner.setVisible(false);
            if (exception != null) {
                statusLabel.setText(i18n("plugin.store.load_failed") + ": " + exception.getMessage());
                showError(i18n("plugin.store.load_failed"), exception.getMessage());
            } else {
                refreshCategories();
                applyFilter();
            }
        }).start();
    }

    private void refreshCategories() {
        CategoryItem selected = categoryBox.getSelectionModel().getSelectedItem();
        Set<String> categories = allItems.stream()
                .map(item -> item.getEntry().getCategory())
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(TreeSet::new));

        List<CategoryItem> items = new ArrayList<>();
        items.add(new CategoryItem(ALL_CATEGORIES, i18n("plugin.store.category.all")));
        categories.forEach(category -> items.add(new CategoryItem(category, category)));
        categoryBox.getItems().setAll(items);

        if (selected != null) {
            categoryBox.getItems().stream()
                    .filter(item -> Objects.equals(item.value, selected.value))
                    .findFirst()
                    .ifPresentOrElse(categoryBox.getSelectionModel()::select, () -> categoryBox.getSelectionModel().selectFirst());
        } else {
            categoryBox.getSelectionModel().selectFirst();
        }
    }

    private void applyFilter() {
        pluginList.getContent().clear();
        String keyword = Optional.ofNullable(searchField.getText()).orElse("").trim().toLowerCase(Locale.ROOT);
        CategoryItem category = categoryBox.getSelectionModel().getSelectedItem();

        List<PluginStoreItem> filtered = allItems.stream()
                .filter(item -> matchesCategory(item, category))
                .filter(item -> matchesKeyword(item, keyword))
                .sorted(Comparator.comparing(item -> Optional.ofNullable(item.getEntry().getName()).orElse(item.getEntry().getId()), String.CASE_INSENSITIVE_ORDER))
                .toList();

        if (filtered.isEmpty()) {
            Label empty = new Label(allItems.isEmpty() ? i18n("plugin.store.empty") : i18n("plugin.store.no_result"));
            empty.setStyle("-fx-padding: 16; -fx-text-fill: gray;");
            pluginList.getContent().add(empty);
        } else {
            filtered.forEach(item -> pluginList.getContent().add(createPluginCard(item)));
        }

        PluginStoreRegistry registry = storeManager.getRegistry();
        String registryName = registry == null ? storeManager.getRegistryUrl() : registry.getName();
        statusLabel.setText(i18n("plugin.store.loaded") + ": " + filtered.size() + "/" + allItems.size() + " " + i18n("plugin.store.plugins") + " - " + registryName);
    }

    private boolean matchesCategory(PluginStoreItem item, CategoryItem category) {
        return category == null || ALL_CATEGORIES.equals(category.value) || Objects.equals(category.value, item.getEntry().getCategory());
    }

    private boolean matchesKeyword(PluginStoreItem item, String keyword) {
        if (keyword.isEmpty()) {
            return true;
        }

        PluginStoreRegistry.PluginStoreEntry entry = item.getEntry();
        return contains(entry.getId(), keyword)
                || contains(entry.getName(), keyword)
                || contains(entry.getAuthor(), keyword)
                || contains(entry.getDescription(), keyword)
                || contains(entry.getCategory(), keyword)
                || (entry.getTags() != null && entry.getTags().stream().anyMatch(tag -> contains(tag, keyword)));
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private VBox createPluginCard(PluginStoreItem item) {
        PluginStoreRegistry.PluginStoreEntry entry = item.getEntry();
        PluginStoreManifest.PluginVersion latestVersion = item.getLatestVersion();
        PluginContainer installed = pluginManager.getPlugin(entry.getId());
        boolean isInstalled = installed != null;
        boolean hasUpdate = storeManager.hasUpdate(installed, latestVersion);

        VBox card = new VBox(8);
        card.setPadding(new Insets(15));
        card.setStyle("-fx-background-color: white; -fx-border-color: #e0e0e0; -fx-border-width: 1; -fx-border-radius: 5; -fx-background-radius: 5;");

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = new Label(Optional.ofNullable(entry.getName()).orElse(entry.getId()));
        nameLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label versionLabel = new Label(latestVersion == null ? i18n("plugin.store.version_unknown") : "v" + latestVersion.getVersion());
        versionLabel.setStyle("-fx-text-fill: #2196F3;");

        Label authorLabel = new Label(i18n("plugin.author") + ": " + Optional.ofNullable(entry.getAuthor()).orElse(""));
        authorLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label stateLabel = new Label(hasUpdate ? i18n("plugin.store.update_available") : isInstalled ? i18n("plugin.store.installed") : "");
        stateLabel.setStyle("-fx-text-fill: " + (hasUpdate ? "#f57c00" : "green") + "; -fx-font-weight: bold;");
        header.getChildren().addAll(nameLabel, versionLabel, authorLabel, spacer, stateLabel);

        Label descLabel = new Label(Optional.ofNullable(entry.getDescription()).orElse(""));
        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-text-fill: #333;");

        HBox tags = new HBox(6);
        tags.setAlignment(Pos.CENTER_LEFT);
        if (StringUtils.isNotBlank(entry.getCategory())) {
            tags.getChildren().add(tagLabel(entry.getCategory()));
        }
        if (entry.getTags() != null) {
            entry.getTags().stream().filter(StringUtils::isNotBlank).limit(5).forEach(tag -> tags.getChildren().add(tagLabel(tag)));
        }

        HBox infoBox = new HBox(15);
        infoBox.setAlignment(Pos.CENTER_LEFT);
        Hyperlink repoLink = new Hyperlink(i18n("plugin.store.repository"));
        repoLink.setOnAction(e -> FXUtils.openLink(entry.getRepository()));
        Label idLabel = new Label("ID: " + entry.getId());
        idLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 11px;");
        infoBox.getChildren().addAll(repoLink, idLabel);

        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);

        JFXButton detailsButton = new JFXButton(i18n("plugin.store.details"));
        detailsButton.setOnAction(e -> showPluginDetails(item));

        JFXButton installButton = new JFXButton(hasUpdate ? i18n("plugin.store.update") : isInstalled ? i18n("plugin.store.reinstall") : i18n("plugin.store.install"));
        installButton.getStyleClass().add("jfx-button-raised");
        installButton.setDisable(latestVersion == null);
        installButton.setOnAction(e -> installPlugin(item));
        buttonBox.getChildren().addAll(detailsButton, installButton);

        card.getChildren().addAll(header, descLabel);
        if (!tags.getChildren().isEmpty()) {
            card.getChildren().add(tags);
        }
        card.getChildren().addAll(infoBox, buttonBox);
        return card;
    }

    private Label tagLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-padding: 2 8 2 8; -fx-background-color: #eeeeee; -fx-background-radius: 999; -fx-text-fill: #666; -fx-font-size: 11px;");
        return label;
    }

    private void installPlugin(PluginStoreItem item) {
        PluginStoreRegistry.PluginStoreEntry entry = item.getEntry();
        PluginStoreManifest.PluginVersion latestVersion = item.getLatestVersion();
        if (latestVersion == null) {
            showError(i18n("plugin.store.install_failed"), i18n("plugin.store.no_version"));
            return;
        }

        String pluginName = Optional.ofNullable(entry.getName()).orElse(entry.getId());
        PluginDialogs.confirmPluginInstall(pluginName, confirmed -> {
            if (!confirmed) {
                return;
            }

            Alert progressAlert = new Alert(Alert.AlertType.INFORMATION);
            progressAlert.setTitle(i18n("plugin.store.installing"));
            progressAlert.setHeaderText(i18n("plugin.store.installing") + ": " + pluginName);
            progressAlert.setContentText(i18n("plugin.store.downloading"));
            progressAlert.initOwner(Controllers.getStage());
            progressAlert.show();

            Task.supplyAsync(() -> {
                try {
                    PluginContainer installed = pluginManager.getPlugin(entry.getId());
                    if (installed != null) {
                        pluginManager.uninstallPlugin(entry.getId());
                    }

                    Path pluginFile = storeManager.downloadPlugin(entry.getId(), latestVersion, pluginManager.getPluginsDirectory());

                    // Prepare plugin in background thread (extract, load classes)
                    return pluginManager.preparePlugin(pluginFile);
                } catch (Exception e) {
                    LOG.error("Failed to install plugin: " + entry.getId(), e);
                    throw new RuntimeException(e);
                }
            }).whenComplete(Schedulers.javafx(), (prepared, exception) -> {
                progressAlert.close();
                if (exception != null) {
                    showError(i18n("plugin.store.install_failed"), exception.getMessage());
                } else {
                    // Register and enable on JavaFX thread
                    try {
                        PluginContainer container = pluginManager.registerPreparedPlugin(prepared);
                        pluginManager.enablePlugin(container.getManifest().getId());
                        PluginDialogs.showInstallFinishedAndOfferRestart(pluginName);
                        applyFilter();
                    } catch (Exception e) {
                        LOG.error("Failed to register plugin", e);
                        showError(i18n("plugin.store.install_failed"), e.getMessage());
                    }
                }
            }).start();
        });
    }

    private void showPluginDetails(PluginStoreItem item) {
        PluginStoreRegistry.PluginStoreEntry entry = item.getEntry();
        PluginStoreManifest manifest = item.getManifest();
        PluginStoreManifest.PluginVersion latestVersion = item.getLatestVersion();
        PluginContainer installed = pluginManager.getPlugin(entry.getId());

        Alert detailsAlert = new Alert(Alert.AlertType.INFORMATION);
        detailsAlert.setTitle(entry.getName());
        detailsAlert.setHeaderText(entry.getName() + " - " + i18n("plugin.store.details"));

        StringBuilder content = new StringBuilder();
        content.append(i18n("plugin.author")).append(": ").append(Optional.ofNullable(entry.getAuthor()).orElse("")).append("\n");
        content.append(i18n("plugin.description")).append(": ").append(Optional.ofNullable(entry.getDescription()).orElse("")).append("\n");
        content.append("ID: ").append(entry.getId()).append("\n");
        if (StringUtils.isNotBlank(entry.getCategory())) {
            content.append(i18n("plugin.store.category")).append(": ").append(entry.getCategory()).append("\n");
        }
        if (entry.getTags() != null && !entry.getTags().isEmpty()) {
            content.append(i18n("plugin.store.tags")).append(": ").append(String.join(", ", entry.getTags())).append("\n");
        }
        content.append(i18n("plugin.store.repository")).append(": ").append(entry.getRepository()).append("\n");
        if (installed != null) {
            content.append(i18n("plugin.store.installed_version")).append(": ").append(installed.getManifest().getVersion()).append("\n");
        }
        if (latestVersion != null) {
            content.append(i18n("plugin.store.latest_version")).append(": ").append(latestVersion.getVersion()).append("\n");
            if (latestVersion.getReleaseDate() != null) {
                content.append(i18n("plugin.store.release_date")).append(": ").append(latestVersion.getReleaseDate()).append("\n");
            }
            if (latestVersion.getSize() != null) {
                content.append(i18n("plugin.store.size")).append(": ").append(formatSize(latestVersion.getSize())).append("\n");
            }
            if (latestVersion.getMinLauncherVersion() != null) {
                content.append(i18n("plugin.store.min_launcher_version")).append(": ").append(latestVersion.getMinLauncherVersion()).append("\n");
            }
            if (latestVersion.getReleaseNotes() != null) {
                content.append("\n").append(i18n("plugin.store.release_notes")).append(":\n").append(latestVersion.getReleaseNotes()).append("\n");
            }
        }
        if (manifest != null && StringUtils.isNotBlank(manifest.getLicense())) {
            content.append("\n").append(i18n("plugin.store.license")).append(": ").append(manifest.getLicense()).append("\n");
        }

        detailsAlert.setContentText(content.toString());
        detailsAlert.initOwner(Controllers.getStage());

        ButtonType openRepoButton = new ButtonType(i18n("plugin.store.open_repository"), ButtonBar.ButtonData.LEFT);
        detailsAlert.getButtonTypes().add(openRepoButton);

        Optional<ButtonType> result = detailsAlert.showAndWait();
        if (result.isPresent() && result.get() == openRepoButton) {
            FXUtils.openLink(entry.getRepository());
        }
    }

    private String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", size / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", size / 1024.0 / 1024.0);
    }

    private void showStoreSettings() {
        TextInputDialog dialog = new TextInputDialog(storeManager.getRegistryUrl());
        dialog.setTitle(i18n("plugin.store.settings"));
        dialog.setHeaderText(i18n("plugin.store.custom_registry"));
        dialog.setContentText(i18n("plugin.store.registry_url") + ":");
        dialog.initOwner(Controllers.getStage());

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(url -> {
            if (!url.trim().isEmpty()) {
                storeManager.addCustomRegistry(url.trim());
                sourceBox.getItems().setAll(storeManager.getRegistryUrls());
                sourceBox.getSelectionModel().select(url.trim());
                loadPluginStore();
            }
        });
    }

    private void showError(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.initOwner(Controllers.getStage());
            alert.showAndWait();
        });
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    @Override
    public void refresh() {
        // Lazy load: only load store on first refresh (when page is actually shown)
        if (!initialized) {
            initialized = true;
            loadPluginStore();
        } else {
            loadPluginStore();
        }
    }

    private static final class CategoryItem {
        final String value;
        final String label;

        CategoryItem(String value, String label) {
            this.value = value;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}

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
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.plugin.PluginContainer;
import org.jackhuang.hmcl.plugin.PluginManager;
import org.jackhuang.hmcl.plugin.loader.JavaScriptPluginLoader;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public class PluginManagementPage extends VBox implements DecoratorPage {

    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("plugin.manage")));

    private final PluginManager pluginManager = PluginManager.getInstance();
    private final ComponentList pluginList = new ComponentList();
    private final Label emptyHint = new Label();
    private final Label jsEngineStatus = new Label();

    public PluginManagementPage() {
        setSpacing(10);
        setPadding(new Insets(10));

        // Top bar with buttons
        HBox topBar = new HBox(10);
        topBar.setAlignment(Pos.CENTER_LEFT);

        JFXButton installButton = new JFXButton(i18n("plugin.install"));
        installButton.getStyleClass().add("jfx-button-raised");
        installButton.setOnAction(e -> installPlugin());

        JFXButton refreshButton = new JFXButton(i18n("plugin.refresh"));
        refreshButton.setOnAction(e -> refresh());

        JFXButton openFolderButton = new JFXButton(i18n("plugin.open_folder"));
        openFolderButton.setOnAction(e -> FXUtils.openFolder(pluginManager.getPluginsDirectory()));

        topBar.getChildren().addAll(installButton, refreshButton, openFolderButton);

        // JavaScript engine status
        updateJsEngineStatus();
        jsEngineStatus.setWrapText(true);
        jsEngineStatus.setStyle("-fx-padding: 10; -fx-background-color: #f0f0f0; -fx-background-radius: 5;");

        // Empty hint
        emptyHint.setText(i18n("plugin.empty"));
        emptyHint.setStyle("-fx-text-fill: gray; -fx-font-size: 14px;");

        // Plugin list
        ScrollPane scrollPane = new ScrollPane(pluginList);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        getChildren().addAll(topBar, jsEngineStatus, scrollPane);

        refresh();
    }

    private void updateJsEngineStatus() {
        if (JavaScriptPluginLoader.isEngineAvailable()) {
            jsEngineStatus.setText(i18n("plugin.js_engine_available") + ": " + JavaScriptPluginLoader.getEngineName());
            jsEngineStatus.setStyle("-fx-padding: 10; -fx-background-color: #d4edda; -fx-background-radius: 5; -fx-text-fill: #155724;");
            jsEngineStatus.setGraphic(null);
        } else if (org.jackhuang.hmcl.plugin.loader.NodeJSManager.isNodeInstalled()) {
            jsEngineStatus.setText(i18n("plugin.nodejs_installed") + ": Node.js " + org.jackhuang.hmcl.plugin.loader.NodeJSManager.NODE_VERSION);
            jsEngineStatus.setStyle("-fx-padding: 10; -fx-background-color: #d4edda; -fx-background-radius: 5; -fx-text-fill: #155724;");

            JFXButton uninstallBtn = new JFXButton(i18n("plugin.nodejs_uninstall"));
            uninstallBtn.setOnAction(e -> uninstallNodeJS());
            jsEngineStatus.setGraphic(uninstallBtn);
        } else {
            jsEngineStatus.setText(i18n("plugin.js_engine_unavailable") + "\n" +
                    i18n("plugin.js_engine_info") + ": " + JavaScriptPluginLoader.getSystemInfo());
            jsEngineStatus.setStyle("-fx-padding: 10; -fx-background-color: #fff3cd; -fx-background-radius: 5; -fx-text-fill: #856404;");

            JFXButton downloadLink = new JFXButton(i18n("plugin.js_engine_download"));
            downloadLink.getStyleClass().add("jfx-button-raised");
            downloadLink.setOnAction(e -> downloadNodeJS());
            jsEngineStatus.setGraphic(downloadLink);
        }
    }

    private void downloadNodeJS() {
        String downloadUrl = org.jackhuang.hmcl.plugin.loader.NodeJSManager.getDownloadUrl();
        if (downloadUrl == null) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(i18n("plugin.js_engine_download"));
            alert.setHeaderText(i18n("plugin.js_engine_unsupported_platform"));
            alert.setContentText(org.jackhuang.hmcl.plugin.loader.NodeJSManager.getPlatformDescription());
            alert.initOwner(Controllers.getStage());
            alert.showAndWait();
            return;
        }

        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle(i18n("plugin.js_engine_download_header"));
        confirmation.setHeaderText(null);
        confirmation.setContentText(i18n("plugin.js_engine_download_prompt",
                org.jackhuang.hmcl.plugin.loader.NodeJSManager.NODE_VERSION,
                org.jackhuang.hmcl.plugin.loader.NodeJSManager.getPlatformDescription()));
        confirmation.initOwner(Controllers.getStage());

        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            Alert progressAlert = new Alert(Alert.AlertType.INFORMATION);
            progressAlert.setTitle(i18n("plugin.js_engine_downloading"));
            progressAlert.setHeaderText(i18n("plugin.js_engine_downloading"));
            progressAlert.setContentText(i18n("plugin.js_engine_extracting"));
            progressAlert.initOwner(Controllers.getStage());
            progressAlert.show();

            Task.runAsync(() -> {
                try {
                    org.jackhuang.hmcl.plugin.loader.NodeJSManager.downloadAndInstall();
                } catch (IOException e) {
                    LOG.error("Failed to download Node.js", e);
                    throw new RuntimeException(e);
                }
            }).whenComplete(Schedulers.javafx(), (r, exception) -> {
                progressAlert.close();
                if (exception != null) {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle(i18n("plugin.js_engine_download"));
                    alert.setHeaderText(i18n("plugin.js_engine_install_failed"));
                    alert.setContentText(exception.getMessage());
                    alert.initOwner(Controllers.getStage());
                    alert.showAndWait();
                } else {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle(i18n("plugin.js_engine_download"));
                    alert.setHeaderText(i18n("plugin.js_engine_installed"));
                    alert.setContentText(i18n("plugin.js_engine_installed"));
                    alert.initOwner(Controllers.getStage());
                    alert.showAndWait();
                    updateJsEngineStatus();
                }
            }).start();
        }
    }

    private void uninstallNodeJS() {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle(i18n("plugin.nodejs_uninstall"));
        confirmation.setHeaderText(null);
        confirmation.setContentText(i18n("plugin.nodejs_uninstall") + "?");
        confirmation.initOwner(Controllers.getStage());

        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            Task.runAsync(() -> {
                try {
                    org.jackhuang.hmcl.plugin.loader.NodeJSManager.uninstall();
                } catch (IOException e) {
                    LOG.error("Failed to uninstall Node.js", e);
                    throw new RuntimeException(e);
                }
            }).whenComplete(Schedulers.javafx(), (r, exception) -> {
                if (exception != null) {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle(i18n("plugin.nodejs_uninstall"));
                    alert.setHeaderText(i18n("plugin.uninstall_failed"));
                    alert.setContentText(exception.getMessage());
                    alert.initOwner(Controllers.getStage());
                    alert.showAndWait();
                } else {
                    updateJsEngineStatus();
                }
            }).start();
        }
    }

    private void showJsDownloadInfo() {
        downloadNodeJS();
    }

    public void refresh() {
        pluginList.getContent().clear();

        if (pluginManager.getPlugins().isEmpty()) {
            pluginList.getContent().add(emptyHint);
        } else {
            for (PluginContainer container : pluginManager.getPlugins()) {
                pluginList.getContent().add(createPluginItem(container));
            }
        }
    }

    private VBox createPluginItem(PluginContainer container) {
        VBox item = new VBox(5);
        item.setPadding(new Insets(10));

        boolean markedForUninstall = pluginManager.isMarkedForUninstall(container.getManifest().getId());

        if (markedForUninstall) {
            item.setStyle("-fx-background-color: #fff5f5; -fx-border-color: #ffcdd2; -fx-border-width: 1; -fx-border-radius: 5; -fx-background-radius: 5;");
        } else {
            item.setStyle("-fx-background-color: white; -fx-border-color: #e0e0e0; -fx-border-width: 1; -fx-border-radius: 5; -fx-background-radius: 5;");
        }

        // Header with name and version
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = new Label(container.getManifest().getName());
        if (markedForUninstall) {
            nameLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-strikethrough: true; -fx-font-style: italic; -fx-text-fill: #999;");
        } else {
            nameLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        }

        Label versionLabel = new Label("v" + container.getManifest().getVersion());
        if (markedForUninstall) {
            versionLabel.setStyle("-fx-text-fill: #999; -fx-strikethrough: true; -fx-font-style: italic;");
        } else {
            versionLabel.setStyle("-fx-text-fill: gray;");
        }

        String typeText = container.getManifest().getType().name()
                + (container.getManifest().hasMixins() ? " + MIXIN" : "");
        Label typeLabel = new Label("[" + typeText + "]");
        typeLabel.setStyle("-fx-text-fill: #2196F3; -fx-font-size: 12px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Status indicator
        Label statusLabel = new Label();
        if (markedForUninstall) {
            statusLabel.setText(i18n("plugin.pending_uninstall"));
            statusLabel.setStyle("-fx-text-fill: #d32f2f; -fx-font-weight: bold;");
        } else if (container.isRestartRequired()) {
            statusLabel.setText(i18n("plugin.restart_pending"));
            statusLabel.setStyle("-fx-text-fill: #e65100; -fx-font-weight: bold;");
        } else if (container.isEnabled()) {
            statusLabel.setText(i18n("plugin.enabled"));
            statusLabel.setStyle("-fx-text-fill: green; -fx-font-weight: bold;");
        } else {
            statusLabel.setText(i18n("plugin.disabled"));
            statusLabel.setStyle("-fx-text-fill: gray;");
        }

        header.getChildren().addAll(nameLabel, versionLabel, typeLabel, spacer, statusLabel);

        // Info
        VBox info = new VBox(3);

        if (!container.getManifest().getDescription().isEmpty()) {
            Label descLabel = new Label(container.getManifest().getDescription());
            descLabel.setWrapText(true);
            descLabel.setStyle("-fx-text-fill: #666;");
            info.getChildren().add(descLabel);
        }

        if (!container.getManifest().getAuthor().isEmpty()) {
            Label authorLabel = new Label(i18n("plugin.author") + ": " + container.getManifest().getAuthor());
            authorLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 12px;");
            info.getChildren().add(authorLabel);
        }

        Label idLabel = new Label("ID: " + container.getManifest().getId());
        idLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 11px;");
        info.getChildren().add(idLabel);

        // Buttons
        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        if (!markedForUninstall) {
            JFXButton toggleButton = new JFXButton();
            if (container.isEnabled()) {
                toggleButton.setText(i18n("plugin.disable"));
                toggleButton.setOnAction(e -> {
                    pluginManager.disablePlugin(container.getManifest().getId());
                    Platform.runLater(() -> {
                        refresh();
                        PluginDialogs.showRestartRequired(i18n("plugin.restart_required.disable"));
                    });
                });
            } else {
                toggleButton.setText(i18n("plugin.enable"));
                toggleButton.getStyleClass().add("jfx-button-raised");
                toggleButton.setOnAction(e -> {
                    pluginManager.enablePlugin(container.getManifest().getId());
                    Platform.runLater(() -> {
                        refresh();
                        PluginDialogs.showRestartRequired(i18n("plugin.restart_required.enable"));
                    });
                });
            }

            JFXButton uninstallButton = new JFXButton(i18n("plugin.uninstall"));
            uninstallButton.setStyle("-fx-text-fill: red;");
            uninstallButton.setOnAction(e -> uninstallPlugin(container));

            buttons.getChildren().addAll(toggleButton, uninstallButton);
        }

        item.getChildren().addAll(header, info, buttons);

        return item;
    }

    private void installPlugin() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(i18n("plugin.install"));
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(i18n("plugin.file"), "*.npl")
        );

        File file = fileChooser.showOpenDialog(Controllers.getStage());
        if (file != null) {
            String pluginName = file.getName();
            Path pluginPath = file.toPath();
            Path targetPath = pluginManager.getPluginsDirectory().resolve(file.getName());

            // Show warning dialog asynchronously
            PluginDialogs.confirmPluginInstall(pluginName, confirmed -> {
                if (!confirmed) {
                    return;
                }

                // User confirmed, proceed with installation
                Task.supplyAsync(() -> {
                    try {
                        // Do file copy and plugin preparation in background thread
                        if (!pluginPath.equals(targetPath)) {
                            Files.copy(pluginPath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        // Prepare plugin (extract ZIP, load classes) - all IO operations
                        return pluginManager.preparePlugin(targetPath);
                    } catch (IOException e) {
                        LOG.error("Failed to prepare plugin", e);
                        throw new RuntimeException(e);
                    }
                }).whenComplete(Schedulers.javafx(), (prepared, exception) -> {
                    if (exception != null) {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle(i18n("plugin.install"));
                        alert.setHeaderText(i18n("plugin.install_failed"));
                        alert.setContentText(exception.getMessage());
                        alert.initOwner(Controllers.getStage());
                        alert.showAndWait();
                    } else {
                        // Register and enable plugin on JavaFX thread
                        try {
                            PluginContainer container = pluginManager.registerPreparedPlugin(prepared);
                            pluginManager.enablePlugin(container.getManifest().getId());
                            refresh();
                            PluginDialogs.showInstallFinishedAndOfferRestart(pluginName);
                        } catch (Exception e) {
                            LOG.error("Failed to register plugin", e);
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setTitle(i18n("plugin.install"));
                            alert.setHeaderText(i18n("plugin.install_failed"));
                            alert.setContentText(e.getMessage());
                            alert.initOwner(Controllers.getStage());
                            alert.showAndWait();
                        }
                    }
                }).start();
            });
        }
    }

    private void uninstallPlugin(PluginContainer container) {
        boolean wasEnabled = container.isEnabled();

        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle(i18n("plugin.uninstall"));
        confirmation.setHeaderText(i18n("plugin.uninstall_confirm"));
        confirmation.setContentText(container.getManifest().getName() + " v" + container.getManifest().getVersion());
        confirmation.initOwner(Controllers.getStage());

        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            String pluginId = container.getManifest().getId();

            if (wasEnabled || pluginManager.requiresRestartForUninstall(pluginId)) {
                // Mark for uninstall and require restart
                pluginManager.markForUninstall(pluginId);
                refresh();
                PluginDialogs.showRestartRequired(i18n("plugin.restart_required.uninstall"));
            } else {
                // Directly uninstall in background if disabled
                Task.runAsync(() -> {
                    try {
                        pluginManager.uninstallPlugin(pluginId);
                    } catch (IOException e) {
                        LOG.error("Failed to uninstall plugin", e);
                        throw new RuntimeException(e);
                    }
                }).whenComplete(Schedulers.javafx(), (r, exception) -> {
                    if (exception != null) {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle(i18n("plugin.uninstall"));
                        alert.setHeaderText(i18n("plugin.uninstall_failed"));
                        alert.setContentText(exception.getMessage());
                        alert.initOwner(Controllers.getStage());
                        alert.showAndWait();
                    } else {
                        refresh();
                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
                        alert.setTitle(i18n("plugin.uninstall"));
                        alert.setHeaderText(null);
                        alert.setContentText(i18n("plugin.uninstall_success"));
                        alert.initOwner(Controllers.getStage());
                        alert.showAndWait();
                    }
                }).start();
            }
        }
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }
}

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
import javafx.application.Platform;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.util.Restarter;

import java.io.IOException;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

final class PluginDialogs {

    static void confirmPluginInstall(String pluginName, Consumer<Boolean> callback) {
        Platform.runLater(() -> {
            MessageDialogPane pane = new MessageDialogPane(
                    i18n("plugin.install.warning.content", pluginName),
                    i18n("plugin.install.warning.title"),
                    MessageDialogPane.MessageType.WARNING
            );

            JFXButton installButton = new JFXButton(i18n("plugin.install.warning.install_anyway"));
            installButton.getStyleClass().add("dialog-accept");
            installButton.setOnAction(e -> callback.accept(true));

            JFXButton cancelButton = new JFXButton(i18n("button.cancel"));
            cancelButton.getStyleClass().add("dialog-cancel");
            cancelButton.setOnAction(e -> callback.accept(false));

            pane.addButton(installButton);
            pane.addButton(cancelButton);
            pane.setCancelButton(cancelButton);

            Controllers.dialog(pane);
        });
    }

    static void showInstallFinishedAndOfferRestart(String pluginName) {
        Platform.runLater(() -> {
            MessageDialogPane pane = new MessageDialogPane(
                    i18n("plugin.install.complete.content", pluginName),
                    i18n("plugin.install.complete.title"),
                    MessageDialogPane.MessageType.SUCCESS
            );

            JFXButton restartButton = new JFXButton(i18n("plugin.install.restart.now"));
            restartButton.getStyleClass().add("dialog-accept");
            restartButton.setOnAction(e -> restartLauncher());

            JFXButton laterButton = new JFXButton(i18n("plugin.install.restart.later"));
            laterButton.getStyleClass().add("dialog-cancel");

            pane.addButton(restartButton);
            pane.addButton(laterButton);
            pane.setCancelButton(laterButton);

            Controllers.dialog(pane);
        });
    }

    static void showRestartRequired(String message) {
        Platform.runLater(() -> {
            MessageDialogPane pane = new MessageDialogPane(
                    message,
                    i18n("plugin.restart_required.title"),
                    MessageDialogPane.MessageType.INFO
            );

            JFXButton restartButton = new JFXButton(i18n("plugin.install.restart.now"));
            restartButton.getStyleClass().add("dialog-accept");
            restartButton.setOnAction(e -> restartLauncher());

            JFXButton laterButton = new JFXButton(i18n("plugin.install.restart.later"));
            laterButton.getStyleClass().add("dialog-cancel");

            pane.addButton(restartButton);
            pane.addButton(laterButton);
            pane.setCancelButton(laterButton);

            Controllers.dialog(pane);
        });
    }

    private static void restartLauncher() {
        try {
            Controllers.onApplicationStop();
            Restarter.restartSelf();
            Platform.exit();
        } catch (IOException e) {
            LOG.warning("Failed to restart self", e);
            Platform.runLater(() -> {
                MessageDialogPane errorPane = new MessageDialogPane(
                        e.getMessage(),
                        i18n("plugin.install.restart.failed"),
                        MessageDialogPane.MessageType.ERROR
                );
                JFXButton okButton = new JFXButton(i18n("button.ok"));
                okButton.getStyleClass().add("dialog-accept");
                errorPane.addButton(okButton);
                Controllers.dialog(errorPane);
            });
        }
    }

    private PluginDialogs() {
    }
}

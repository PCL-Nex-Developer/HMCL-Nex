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
package org.jackhuang.hmcl.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXTextArea;
import com.jfoenix.controls.JFXTextField;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Builds a real JavaFX control tree from the restricted {@code hmcl-ui-v1}
 * JSON protocol emitted by an isolated Node.js plugin.
 */
public final class JavaScriptPluginPage extends StackPane implements DecoratorPage {

    @FunctionalInterface
    public interface EventHandler {
        void handle(String eventId, Map<String, String> values, Consumer<JsonObject> callback);
    }

    private static final int MAX_DEPTH = 20;
    private static final int MAX_NODES = 500;

    private final ReadOnlyObjectWrapper<State> state;
    private final EventHandler eventHandler;
    private final Map<String, Node> nodesById = new LinkedHashMap<>();
    private int nodeCount;

    public JavaScriptPluginPage(String title, JsonObject page, EventHandler eventHandler) {
        this.state = new ReadOnlyObjectWrapper<>(State.fromTitle(title));
        this.eventHandler = eventHandler;

        Node content;
        try {
            content = buildNode(page, 0);
        } catch (RuntimeException e) {
            LOG.warning("Failed to build JavaScript plugin page", e);
            Label error = new Label("The JavaScript plugin returned an invalid hmcl-ui-v1 page: " + e.getMessage());
            error.setWrapText(true);
            content = error;
        }

        StackPane wrapper = new StackPane(content);
        wrapper.setPadding(new Insets(20));

        ScrollPane scrollPane = new ScrollPane(wrapper);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setPannable(true);
        getChildren().setAll(scrollPane);
    }

    private Node buildNode(JsonObject definition, int depth) {
        if (depth > MAX_DEPTH || ++nodeCount > MAX_NODES) {
            throw new IllegalArgumentException("page is too complex");
        }

        String type = string(definition, "type", "label");
        Node node = switch (type) {
            case "vbox" -> buildVBox(definition, depth);
            case "hbox" -> buildHBox(definition, depth);
            case "label", "title", "subtitle" -> buildLabel(definition, type);
            case "button" -> buildButton(definition);
            case "textField" -> buildTextField(definition);
            case "textArea" -> buildTextArea(definition);
            case "checkBox" -> buildCheckBox(definition);
            case "separator" -> new Separator();
            case "spacer" -> buildSpacer(definition);
            default -> throw new IllegalArgumentException("unsupported control type: " + type);
        };

        node.setDisable(bool(definition, "disabled", false));
        if (definition.has("id")) {
            String id = definition.get("id").getAsString();
            if (id.isBlank() || nodesById.putIfAbsent(id, node) != null) {
                throw new IllegalArgumentException("invalid or duplicate control id: " + id);
            }
        }
        return node;
    }

    private VBox buildVBox(JsonObject definition, int depth) {
        VBox box = new VBox(number(definition, "spacing", 12));
        box.setFillWidth(true);
        box.setAlignment(position(definition, Pos.TOP_LEFT));
        box.setPadding(uniformInsets(definition));
        addChildren(box, definition, depth);
        return box;
    }

    private HBox buildHBox(JsonObject definition, int depth) {
        HBox box = new HBox(number(definition, "spacing", 10));
        box.setAlignment(position(definition, Pos.CENTER_LEFT));
        box.setPadding(uniformInsets(definition));
        addChildren(box, definition, depth);
        return box;
    }

    private void addChildren(Pane pane, JsonObject definition, int depth) {
        if (!definition.has("children") || !definition.get("children").isJsonArray()) {
            return;
        }
        JsonArray children = definition.getAsJsonArray("children");
        for (JsonElement child : children) {
            if (!child.isJsonObject()) {
                throw new IllegalArgumentException("child must be an object");
            }
            Node childNode = buildNode(child.getAsJsonObject(), depth + 1);
            pane.getChildren().add(childNode);
            if (bool(child.getAsJsonObject(), "grow", false)) {
                if (pane instanceof VBox) VBox.setVgrow(childNode, Priority.ALWAYS);
                if (pane instanceof HBox) HBox.setHgrow(childNode, Priority.ALWAYS);
            }
        }
    }

    private Label buildLabel(JsonObject definition, String type) {
        Label label = new Label(string(definition, "text", ""));
        label.setWrapText(bool(definition, "wrap", true));
        if ("title".equals(type)) {
            label.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        } else if ("subtitle".equals(type)) {
            label.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        } else {
            String variant = string(definition, "variant", "normal");
            switch (variant) {
                case "secondary" -> label.setStyle("-fx-opacity: 0.7;");
                case "success" -> label.setStyle("-fx-text-fill: #2e7d32;");
                case "warning" -> label.setStyle("-fx-text-fill: #ed6c02;");
                case "error" -> label.setStyle("-fx-text-fill: #d32f2f;");
                default -> { }
            }
        }
        return label;
    }

    private JFXButton buildButton(JsonObject definition) {
        JFXButton button = new JFXButton(string(definition, "text", "Button"));
        if (bool(definition, "primary", false)) {
            button.setButtonType(JFXButton.ButtonType.RAISED);
            button.getStyleClass().add("jfx-button-raised");
        }
        if (definition.has("event")) {
            String eventId = definition.get("event").getAsString();
            button.setOnAction(event -> {
                button.setDisable(true);
                eventHandler.handle(eventId, collectValues(), response -> {
                    button.setDisable(false);
                    applyResponse(response);
                });
            });
        }
        return button;
    }

    private JFXTextField buildTextField(JsonObject definition) {
        JFXTextField field = new JFXTextField(string(definition, "text", ""));
        field.setPromptText(string(definition, "prompt", ""));
        return field;
    }

    private JFXTextArea buildTextArea(JsonObject definition) {
        JFXTextArea area = new JFXTextArea(string(definition, "text", ""));
        area.setPromptText(string(definition, "prompt", ""));
        area.setWrapText(bool(definition, "wrap", true));
        area.setPrefRowCount((int) number(definition, "rows", 4));
        return area;
    }

    private CheckBox buildCheckBox(JsonObject definition) {
        CheckBox checkBox = new CheckBox(string(definition, "text", ""));
        checkBox.setSelected(bool(definition, "selected", false));
        return checkBox;
    }

    private Region buildSpacer(JsonObject definition) {
        Region spacer = new Region();
        spacer.setMinSize(number(definition, "width", 0), number(definition, "height", 0));
        return spacer;
    }

    private Map<String, String> collectValues() {
        Map<String, String> values = new LinkedHashMap<>();
        nodesById.forEach((id, node) -> {
            if (node instanceof TextInputControl input) {
                values.put(id, input.getText());
            } else if (node instanceof CheckBox checkBox) {
                values.put(id, Boolean.toString(checkBox.isSelected()));
            }
        });
        return values;
    }

    private void applyResponse(JsonObject response) {
        if (response == null || !response.has("actions") || !response.get("actions").isJsonArray()) {
            return;
        }
        for (JsonElement element : response.getAsJsonArray("actions")) {
            if (!element.isJsonObject()) continue;
            JsonObject action = element.getAsJsonObject();
            String type = string(action, "type", "");
            switch (type) {
                case "setText" -> setNodeText(string(action, "target", ""), string(action, "text", ""));
                case "setDisabled" -> {
                    Node target = nodesById.get(string(action, "target", ""));
                    if (target != null) target.setDisable(bool(action, "disabled", true));
                }
                case "dialog" -> showDialog(action);
                default -> LOG.warning("Unknown hmcl-ui-v1 action: " + type);
            }
        }
    }

    private void setNodeText(String id, String text) {
        Node node = nodesById.get(id);
        if (node instanceof Label label) label.setText(text);
        else if (node instanceof ButtonBase button) button.setText(text);
        else if (node instanceof TextInputControl input) input.setText(text);
    }

    private void showDialog(JsonObject action) {
        MessageType type;
        try {
            type = MessageType.valueOf(string(action, "level", "INFO").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            type = MessageType.INFO;
        }
        Controllers.dialog(string(action, "message", ""), string(action, "title", null), type);
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
    }

    private static double number(JsonObject object, String key, double fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsDouble() : fallback;
    }

    private static Insets uniformInsets(JsonObject object) {
        double padding = Math.max(0, number(object, "padding", 0));
        return new Insets(padding);
    }

    private static Pos position(JsonObject object, Pos fallback) {
        try {
            return Pos.valueOf(string(object, "alignment", fallback.name()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }
}

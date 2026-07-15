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
package org.jackhuang.hmcl.plugin.loader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import org.jackhuang.hmcl.plugin.Plugin;
import org.jackhuang.hmcl.plugin.PluginContext;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.OperatingSystem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Loader for JavaScript plugins.
 * <p>
 * JavaScript plugins are executed with the HMCL-managed Node.js runtime
 * ({@code .hmcl/nodejs/current}), which is downloaded via {@link NodeJSManager}.
 * System-installed Node.js or other JavaScript engines are deliberately
 * ignored to avoid version compatibility issues.
 */
public class JavaScriptPluginLoader implements PluginLoader {

    /**
     * Whether the managed JavaScript runtime is available.
     * Never checks system-installed runtimes.
     */
    public static boolean isEngineAvailable() {
        return NodeJSManager.isNodeInstalled();
    }

    public static String getEngineName() {
        return "Node.js " + NodeJSManager.NODE_VERSION + " (HMCL managed)";
    }

    public static String getSystemInfo() {
        return OperatingSystem.CURRENT_OS.getCheckedName() + " " +
               Architecture.SYSTEM_ARCH.getCheckedName() + " " +
               System.getProperty("java.version");
    }

    public static String getDownloadRecommendation() {
        return "JavaScript Runtime Not Installed\n\n" +
               "Detected System: " + NodeJSManager.getPlatformDescription() + "\n\n" +
               "HMCL can automatically download Node.js " + NodeJSManager.NODE_VERSION + "\n" +
               "into the launcher directory (.hmcl/nodejs).\n\n" +
               "Click the \"Download Node.js Runtime\" button in Plugin Management.\n" +
               "System-installed Node.js will NOT be used to avoid version conflicts.";
    }

    @Override
    public Plugin load(PluginManifest manifest, Path extractedDir, Path nplFile) throws IOException {
        if (!isEngineAvailable()) {
            throw new IOException("JavaScript runtime not installed. " + getDownloadRecommendation());
        }

        Path entryFile = extractedDir.resolve(manifest.getEntrypoint());
        if (!Files.exists(entryFile)) {
            throw new IOException("Entry file not found: " + manifest.getEntrypoint());
        }

        return new NodeJSPlugin(manifest, extractedDir, entryFile);
    }

    /**
     * A JavaScript plugin executed with the managed Node.js runtime.
     * <p>
     * Each lifecycle event runs the plugin entry script as
     * {@code node <entry> <event>} with plugin metadata provided via
     * environment variables:
     * <ul>
     *     <li>{@code HMCL_PLUGIN_EVENT} - onLoad / onEnable / onDisable / onUnload</li>
     *     <li>{@code HMCL_PLUGIN_ID} / {@code HMCL_PLUGIN_NAME} / {@code HMCL_PLUGIN_VERSION}</li>
     *     <li>{@code HMCL_PLUGIN_DIR} - extracted plugin directory</li>
     *     <li>{@code HMCL_PLUGIN_DATA_DIR} - persistent data directory for this plugin</li>
     *     <li>{@code HMCL_VERSION} - launcher version</li>
     * </ul>
     */
    private static final class NodeJSPlugin implements Plugin {

        private static final int LIFECYCLE_TIMEOUT_SECONDS = 30;
        private static final String MESSAGE_PREFIX = "HMCL_PLUGIN_MESSAGE:";

        private final PluginManifest manifest;
        private final Path pluginDir;
        private final Path entryFile;
        private final ExecutorService executor;
        private PluginContext context;
        private volatile boolean enabled;

        NodeJSPlugin(PluginManifest manifest, Path pluginDir, Path entryFile) {
            this.manifest = manifest;
            this.pluginDir = pluginDir;
            this.entryFile = entryFile;
            this.executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "hmcl-js-plugin-" + manifest.getId());
                thread.setDaemon(true);
                return thread;
            });
        }

        private String runLifecycleEvent(String event) {
            return runLifecycleEvent(event, Collections.emptyMap());
        }

        private String runLifecycleEvent(String event, Map<String, String> additionalEnvironment) {
            Path nodeExe = NodeJSManager.getNodeExecutable();
            if (nodeExe == null) {
                LOG.warning("Managed Node.js runtime missing; cannot run " + event + " for " + manifest.getId());
                return null;
            }

            List<String> command = new ArrayList<>();
            command.add(nodeExe.toString());
            command.add(entryFile.toString());
            command.add(event);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(pluginDir.toFile());
            pb.redirectErrorStream(true);

            pb.environment().put("HMCL_PLUGIN_EVENT", event);
            pb.environment().put("HMCL_PLUGIN_ID", manifest.getId());
            pb.environment().put("HMCL_PLUGIN_NAME", manifest.getName());
            pb.environment().put("HMCL_PLUGIN_VERSION", manifest.getVersion());
            pb.environment().put("HMCL_PLUGIN_DIR", pluginDir.toString());
            if (context != null) {
                pb.environment().put("HMCL_PLUGIN_DATA_DIR", context.getDataDirectory().toString());
                pb.environment().put("HMCL_VERSION", context.getLauncherVersion());
            }
            pb.environment().putAll(additionalEnvironment);

            try {
                Process process = pb.start();
                ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
                Thread outputReader = new Thread(() -> {
                    try (var in = process.getInputStream()) {
                        in.transferTo(outputBuffer);
                    } catch (IOException e) {
                        LOG.warning("Failed to read JS plugin output for " + manifest.getId(), e);
                    }
                }, "hmcl-js-output-" + manifest.getId());
                outputReader.setDaemon(true);
                outputReader.start();

                boolean finished = process.waitFor(LIFECYCLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                    LOG.warning("JS plugin " + manifest.getId() + " " + event + " timed out after "
                            + LIFECYCLE_TIMEOUT_SECONDS + "s");
                    return null;
                }
                outputReader.join(1000);
                String output = outputBuffer.toString(StandardCharsets.UTF_8).trim();
                if (!output.isEmpty()) {
                    LOG.info("[JS:" + manifest.getId() + ":" + event + "] " + output);
                }
                if (process.exitValue() != 0) {
                    LOG.warning("JS plugin " + manifest.getId() + " " + event
                            + " exited with code " + process.exitValue());
                }
                return output;
            } catch (IOException e) {
                LOG.error("Failed to run JS plugin " + manifest.getId() + " " + event, e);
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warning("Interrupted while running JS plugin " + manifest.getId() + " " + event);
                return null;
            }
        }

        @Override
        public void onLoad(PluginContext context) {
            this.context = context;
            submit(() -> runLifecycleEvent("onLoad"));
        }

        @Override
        public void onEnable() {
            enabled = true;
            submit(() -> {
                JsonObject message = parseProtocolMessage(runLifecycleEvent("onEnable"));
                if (message != null && enabled) {
                    Platform.runLater(() -> {
                        if (enabled) {
                            registerUI(message);
                        }
                    });
                }
            });
        }

        private JsonObject parseProtocolMessage(String output) {
            if (output == null || output.isBlank()) {
                return null;
            }

            JsonObject result = null;
            for (String line : output.split("\\R")) {
                int marker = line.indexOf(MESSAGE_PREFIX);
                if (marker < 0) {
                    continue;
                }

                String json = line.substring(marker + MESSAGE_PREFIX.length()).trim();
                try {
                    JsonObject candidate = JsonParser.parseString(json).getAsJsonObject();
                    if (candidate.has("protocol")
                            && "hmcl-ui-v1".equals(candidate.get("protocol").getAsString())) {
                        result = candidate;
                    }
                } catch (RuntimeException e) {
                    LOG.warning("Ignoring malformed JS plugin message from " + manifest.getId(), e);
                }
            }
            return result;
        }

        private void registerUI(JsonObject message) {
            try {
                if (!message.has("sidebar") || !message.get("sidebar").isJsonObject()) {
                    return;
                }

                JsonObject sidebar = message.getAsJsonObject("sidebar");
                if (!sidebar.has("title") || !sidebar.has("page") || !sidebar.get("page").isJsonObject()) {
                    throw new IllegalArgumentException("sidebar.title and sidebar.page are required");
                }

                String title = sidebar.get("title").getAsString();
                context.registerJavaScriptSidebarItem(title, sidebar.getAsJsonObject("page"), this::handleUIEvent);
                LOG.info("JS plugin " + manifest.getId() + " registered JavaFX sidebar page: " + title);
            } catch (RuntimeException e) {
                LOG.warning("Failed to register JS plugin UI for " + manifest.getId(), e);
            }
        }

        private void handleUIEvent(String eventId, Map<String, String> values, Consumer<JsonObject> callback) {
            if (!enabled) {
                Platform.runLater(() -> callback.accept(null));
                return;
            }

            submit(() -> {
                Map<String, String> environment = Map.of(
                        "HMCL_UI_EVENT_ID", eventId,
                        "HMCL_UI_VALUES", JsonUtils.GSON.toJson(values)
                );
                JsonObject response = parseProtocolMessage(runLifecycleEvent("onUiEvent", environment));
                Platform.runLater(() -> callback.accept(enabled ? response : null));
            });
        }

        private void submit(Runnable action) {
            try {
                executor.execute(action);
            } catch (RejectedExecutionException e) {
                LOG.warning("JS plugin executor is already closed: " + manifest.getId());
            }
        }

        @Override
        public void onDisable() {
            enabled = false;
            submit(() -> runLifecycleEvent("onDisable"));
        }

        @Override
        public void onUnload() {
            enabled = false;
            submit(() -> runLifecycleEvent("onUnload"));
            executor.shutdown();
        }

        @Override
        public PluginManifest getManifest() {
            return manifest;
        }
    }
}

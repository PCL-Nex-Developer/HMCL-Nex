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
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.internal.PluginPackageVersions;
import org.jackhuang.hmcl.plugin.internal.VerifiedPluginPackage;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Loads JavaScript plugins into the HMCL-managed Node.js runtime.
///
/// System-installed runtimes are deliberately ignored to keep plugin execution reproducible.
@NotNullByDefault
public class JavaScriptPluginLoader implements PluginLoader {
    /// Maximum combined standard output and error bytes retained from one lifecycle process.
    private static final int MAX_LIFECYCLE_OUTPUT_BYTES = 1024 * 1024;

    /// Maximum number of executable JavaScript or JSON files retained from one verified package.
    private static final int MAX_MODULE_FILES = 1024;

    /// Maximum combined decoded bytes retained by one verified JavaScript module graph.
    private static final int MAX_MODULE_BYTES = 16 * 1024 * 1024;

    /// Creates the stateless JavaScript plugin loader.
    public JavaScriptPluginLoader() {
    }

    /// Returns whether the managed JavaScript runtime is installed.
    ///
    /// @return whether the managed runtime is available
    public static boolean isEngineAvailable() {
        return NodeJSManager.isNodeInstalled();
    }

    /// Returns the display name of the managed JavaScript runtime.
    ///
    /// @return runtime display name
    public static String getEngineName() {
        return "Node.js " + NodeJSManager.NODE_VERSION + " (HMCL managed)";
    }

    /// Returns a concise operating-system, architecture, and Java runtime description.
    ///
    /// @return current system description
    public static String getSystemInfo() {
        return OperatingSystem.CURRENT_OS.getCheckedName() + " "
                + Architecture.SYSTEM_ARCH.getCheckedName() + " "
                + System.getProperty("java.version");
    }

    /// Returns user-facing guidance for installing the managed runtime.
    ///
    /// @return runtime installation recommendation
    public static String getDownloadRecommendation() {
        return "JavaScript Runtime Not Installed\n\n"
                + "Detected System: " + NodeJSManager.getPlatformDescription() + "\n\n"
                + "HMCL can automatically download Node.js " + NodeJSManager.NODE_VERSION + "\n"
                + "into the launcher directory (.hmcl/nodejs).\n\n"
                + "Click the \"Download Node.js Runtime\" button in Plugin Management.\n"
                + "System-installed Node.js will NOT be used to avoid version conflicts.";
    }

    /// Creates a managed Node.js lifecycle implementation for one extracted plugin package.
    ///
    /// @param manifest validated plugin manifest
    /// @param pluginPackage exact verified package inventory
    /// @param nplFile installed source package
    /// @return JavaScript lifecycle implementation
    /// @throws IOException if the runtime or entry script is unavailable
    @Override
    public Plugin load(
            PluginManifest manifest,
            VerifiedPluginPackage pluginPackage,
            Path nplFile
    ) throws IOException {
        if (!pluginPackage.getIdentity().getSha256().equals(
                PluginPackageVersions.calculateSha256(nplFile)
        )) {
            throw new IOException("JavaScript plugin package changed before entry-point loading: " + nplFile);
        }
        if (!isEngineAvailable()) {
            throw new IOException("JavaScript runtime not installed. " + getDownloadRecommendation());
        }

        byte @Unmodifiable [] lifecycleScript = createVerifiedLifecycleScript(manifest, pluginPackage);
        if (!pluginPackage.getIdentity().getSha256().equals(
                PluginPackageVersions.calculateSha256(nplFile)
        )) {
            throw new IOException("JavaScript plugin package changed during entry-point loading: " + nplFile);
        }
        return new NodeJSPlugin(manifest, lifecycleScript);
    }

    /// Captures every executable loose module and builds a self-contained CommonJS lifecycle bootstrap.
    ///
    /// Standard CommonJS, dynamic-import, VM, and Module API resolution is bound to the captured byte graph.
    /// Node built-ins remain available, and direct access to an already-known absolute filesystem path is outside this
    /// module-integrity layer.
    ///
    /// @param manifest validated JavaScript manifest
    /// @param pluginPackage exact verified package inventory
    /// @return immutable lifecycle bootstrap bytes
    /// @throws IOException if a module changes, the entry is absent, or the graph exceeds its limits
    static byte @Unmodifiable [] createVerifiedLifecycleScript(
            PluginManifest manifest,
            VerifiedPluginPackage pluginPackage
    ) throws IOException {
        @Unmodifiable Map<String, String> modules = readVerifiedJavaScriptModules(manifest, pluginPackage);
        String encodedModules = Base64.getEncoder().encodeToString(
                JsonUtils.GSON.toJson(modules).getBytes(StandardCharsets.UTF_8)
        );
        String entrypoint = JsonUtils.GSON.toJson(manifest.getEntrypoint().replace('\\', '/'));
        String artifactSha256 = JsonUtils.GSON.toJson(pluginPackage.getIdentity().getSha256());
        String bootstrap = """
                'use strict';
                const __hmclModule = require('module');
                const __hmclPath = require('path');
                const __hmclVm = require('vm');
                const __hmclNativeLoad = __hmclModule._load;
                const __hmclNativeResolveFilename = __hmclModule._resolveFilename;
                const __hmclWrap = __hmclModule.wrap;
                const __hmclNativeVmScript = __hmclVm.Script;
                const __hmclNativeCompileFunction = __hmclVm.compileFunction;
                const __hmclModules = JSON.parse(Buffer.from('%s', 'base64').toString('utf8'));
                const __hmclEntry = %s;
                const __hmclRoot = __hmclPath.join(
                        __hmclPath.parse(process.cwd()).root,
                        '\\0hmcl-plugin',
                        %s
                );
                const __hmclCache = Object.create(null);
                const __hmclImportCache = Object.create(null);

                function __hmclVirtualFilename(key) {
                    return __hmclPath.join(__hmclRoot, ...key.split('/'));
                }

                function __hmclVirtualDirectory(key) {
                    return __hmclPath.dirname(__hmclVirtualFilename(key));
                }

                function __hmclVirtualKey(filename) {
                    if (typeof filename !== 'string') return null;
                    const relative = __hmclPath.relative(__hmclRoot, filename);
                    if (!relative || relative === '..' || relative.startsWith('..' + __hmclPath.sep)
                            || __hmclPath.isAbsolute(relative)) {
                        return null;
                    }
                    const key = relative.split(__hmclPath.sep).join('/');
                    const normalized = __hmclPath.posix.normalize(key);
                    if (normalized !== key || normalized === '..' || normalized.startsWith('../')
                            || __hmclPath.posix.isAbsolute(normalized)) {
                        return null;
                    }
                    return normalized;
                }

                function __hmclParentKey(parent) {
                    if (parent && typeof parent.__hmclVirtualPath === 'string') {
                        if (Object.hasOwn(__hmclModules, parent.__hmclVirtualPath)) {
                            return parent.__hmclVirtualPath;
                        }
                        throw new Error('Unrecognized JavaScript plugin virtual parent');
                    }
                    const filenameKey = __hmclVirtualKey(parent && parent.filename);
                    if (filenameKey !== null) {
                        if (Object.hasOwn(__hmclModules, filenameKey)) return filenameKey;
                        throw new Error('Unrecognized JavaScript plugin virtual parent filename');
                    }
                    return __hmclEntry;
                }

                function __hmclResolve(request, parentKey) {
                    if (typeof request !== 'string') {
                        throw new TypeError('JavaScript plugin module request must be a string');
                    }
                    if (__hmclModule.isBuiltin(request)) return null;
                    let candidate;
                    const virtualKey = __hmclVirtualKey(request);
                    if (virtualKey !== null) {
                        candidate = virtualKey;
                    } else if (request.startsWith('./') || request.startsWith('../')
                            || request.startsWith('.\\\\') || request.startsWith('..\\\\')) {
                        candidate = __hmclPath.posix.normalize(__hmclPath.posix.join(
                                __hmclPath.posix.dirname(parentKey || __hmclEntry),
                                request.replaceAll('\\\\', '/')
                        ));
                    } else {
                        throw new Error(
                                'JavaScript plugins may import only Node built-ins and verified relative modules: '
                                        + request
                        );
                    }
                    if (candidate === '..' || candidate.startsWith('../')
                            || __hmclPath.posix.isAbsolute(candidate)) {
                        throw new Error('JavaScript plugin import escapes its verified package: ' + request);
                    }
                    for (const resolved of [
                        candidate,
                        candidate + '.js',
                        candidate + '.cjs',
                        candidate + '.json',
                        candidate + '/index.js',
                        candidate + '/index.cjs',
                        candidate + '/index.json'
                    ]) {
                        if (Object.hasOwn(__hmclModules, resolved)) return resolved;
                    }
                    const error = new Error('Verified JavaScript module was not found: ' + request);
                    error.code = 'MODULE_NOT_FOUND';
                    throw error;
                }

                function __hmclLoadBuiltin(request) {
                    return __hmclNativeLoad.call(__hmclModule, request, module, false);
                }

                function __hmclCreateRequire(parentKey, parent) {
                    const localRequire = function(request) {
                        const key = __hmclResolve(request, parentKey);
                        return key === null
                                ? __hmclLoadBuiltin(request)
                                : __hmclLoadVirtual(key, parent);
                    };
                    localRequire.resolve = function(request) {
                        const key = __hmclResolve(request, parentKey);
                        return key === null
                                ? __hmclNativeResolveFilename.call(__hmclModule, request, module, false)
                                : __hmclVirtualFilename(key);
                    };
                    localRequire.cache = Object.freeze(Object.create(null));
                    localRequire.extensions = Object.freeze(Object.create(null));
                    localRequire.main = undefined;
                    return localRequire;
                }

                function __hmclLoadVirtual(key, parent) {
                    if (Object.hasOwn(__hmclCache, key)) return __hmclCache[key].exports;
                    const source = Buffer.from(__hmclModules[key], 'base64').toString('utf8');
                    if (key.endsWith('.json')) {
                        const loadedJson = {
                            id: __hmclVirtualFilename(key),
                            path: __hmclVirtualDirectory(key),
                            exports: JSON.parse(source),
                            filename: __hmclVirtualFilename(key),
                            loaded: true,
                            children: [],
                            paths: [],
                            parent: parent || null,
                            __hmclVirtualPath: key
                        };
                        loadedJson.require = __hmclCreateRequire(key, loadedJson);
                        __hmclCache[key] = loadedJson;
                        return loadedJson.exports;
                    }
                    const filename = __hmclVirtualFilename(key);
                    const directory = __hmclVirtualDirectory(key);
                    const loaded = {
                        id: filename,
                        path: directory,
                        exports: {},
                        filename,
                        loaded: false,
                        children: [],
                        paths: [],
                        parent: parent || null,
                        __hmclVirtualPath: key
                    };
                    loaded.require = __hmclCreateRequire(key, loaded);
                    __hmclCache[key] = loaded;
                    try {
                        const executableSource = source.startsWith('#!')
                                ? source.replace(/^#!.*(?:\\r?\\n|$)/, '')
                                : source;
                        const script = new __hmclNativeVmScript(__hmclWrap(executableSource), {
                            filename,
                            importModuleDynamically: request => __hmclImportVirtual(request, key)
                        });
                        const compiledWrapper = script.runInThisContext();
                        compiledWrapper.call(
                                loaded.exports,
                                loaded.exports,
                                loaded.require,
                                loaded,
                                filename,
                                directory
                        );
                        loaded.loaded = true;
                        return loaded.exports;
                    } catch (error) {
                        delete __hmclCache[key];
                        throw error;
                    }
                }

                async function __hmclImportVirtual(request, parentKey) {
                    const key = __hmclResolve(request, parentKey);
                    const cacheKey = key === null ? 'builtin:' + request : 'virtual:' + key;
                    if (Object.hasOwn(__hmclImportCache, cacheKey)) {
                        return __hmclImportCache[cacheKey];
                    }
                    const importPromise = (async function() {
                        const value = key === null
                                ? __hmclLoadBuiltin(request)
                                : __hmclLoadVirtual(key, null);
                        const exportNames = ['default', 'module.exports'];
                        if ((typeof value === 'object' && value !== null) || typeof value === 'function') {
                            for (const name of Object.keys(value)) {
                                if (name !== 'default' && name !== 'module.exports') {
                                    exportNames.push(name);
                                }
                            }
                        }
                        const imported = new __hmclVm.SyntheticModule(exportNames, function() {
                            this.setExport('default', value);
                            this.setExport('module.exports', value);
                            for (const name of exportNames.slice(2)) {
                                this.setExport(name, value[name]);
                            }
                        }, {
                            identifier: key === null
                                    ? __hmclRoot + '/builtin/' + request
                                    : __hmclVirtualFilename(key)
                        });
                        await imported.link(function() {
                            throw new Error('Synthetic JavaScript plugin namespaces cannot import dependencies');
                        });
                        await imported.evaluate();
                        return imported;
                    })();
                    __hmclImportCache[cacheKey] = importPromise;
                    try {
                        return await importPromise;
                    } catch (error) {
                        delete __hmclImportCache[cacheKey];
                        throw error;
                    }
                }

                function __hmclGuardedLoad(request, parent, isMain) {
                    const key = __hmclResolve(request, __hmclParentKey(parent));
                    return key === null
                            ? __hmclNativeLoad.call(this, request, parent, isMain)
                            : __hmclLoadVirtual(key, parent);
                }

                function __hmclGuardedResolveFilename(request, parent, isMain) {
                    const key = __hmclResolve(request, __hmclParentKey(parent));
                    return key === null
                            ? __hmclNativeResolveFilename.call(this, request, parent, isMain)
                            : __hmclVirtualFilename(key);
                }

                function __hmclRejectDiskModuleLoad(filename) {
                    throw new Error('JavaScript plugins cannot load mutable filesystem modules: ' + filename);
                }

                function __hmclRejectGeneratedDynamicImport(request) {
                    throw new Error(
                            'Dynamic imports from generated vm source cannot load mutable modules: ' + request
                    );
                }

                function __hmclGuardVmOptions(options) {
                    const guarded = typeof options === 'string'
                            ? { filename: options }
                            : Object.assign({}, options || {});
                    guarded.importModuleDynamically = __hmclRejectGeneratedDynamicImport;
                    return guarded;
                }

                const __hmclGuardedVmScript = new Proxy(__hmclNativeVmScript, {
                    apply(target, thisArgument, argumentsList) {
                        return Reflect.construct(target, [
                            argumentsList[0],
                            __hmclGuardVmOptions(argumentsList[1])
                        ]);
                    },
                    construct(target, argumentsList, newTarget) {
                        return Reflect.construct(target, [
                            argumentsList[0],
                            __hmclGuardVmOptions(argumentsList[1])
                        ], newTarget);
                    }
                });

                function __hmclLockProperty(target, name, value) {
                    Object.defineProperty(target, name, {
                        value,
                        writable: false,
                        configurable: false
                    });
                }

                __hmclLockProperty(__hmclNativeVmScript.prototype, 'constructor', __hmclGuardedVmScript);
                __hmclLockProperty(__hmclVm, 'Script', __hmclGuardedVmScript);
                __hmclLockProperty(__hmclVm, 'createScript', function(code, options) {
                    return new __hmclGuardedVmScript(code, options);
                });
                __hmclLockProperty(__hmclVm, 'runInThisContext', function(code, options) {
                    return new __hmclGuardedVmScript(code, options).runInThisContext(options);
                });
                __hmclLockProperty(__hmclVm, 'runInContext', function(code, context, options) {
                    return new __hmclGuardedVmScript(code, options).runInContext(context, options);
                });
                __hmclLockProperty(__hmclVm, 'runInNewContext', function(code, context, options) {
                    return new __hmclGuardedVmScript(code, options).runInNewContext(context, options);
                });
                __hmclLockProperty(__hmclVm, 'compileFunction', function(code, parameters, options) {
                    return __hmclNativeCompileFunction(code, parameters, __hmclGuardVmOptions(options));
                });

                Object.defineProperty(__hmclModule, '_load', {
                    value: __hmclGuardedLoad,
                    writable: false,
                    configurable: false
                });
                Object.defineProperty(__hmclModule, '_resolveFilename', {
                    value: __hmclGuardedResolveFilename,
                    writable: false,
                    configurable: false
                });
                Object.defineProperty(__hmclModule.prototype, 'load', {
                    value: __hmclRejectDiskModuleLoad,
                    writable: false,
                    configurable: false
                });
                Object.defineProperty(__hmclModule.prototype, '_compile', {
                    value: __hmclRejectDiskModuleLoad,
                    writable: false,
                    configurable: false
                });
                __hmclLoadVirtual(__hmclEntry, null);
                """.formatted(encodedModules, entrypoint, artifactSha256);
        return bootstrap.getBytes(StandardCharsets.UTF_8);
    }

    /// Captures the complete bounded loose CommonJS module graph from verified file reads.
    ///
    /// @param manifest validated JavaScript manifest
    /// @param pluginPackage exact verified package inventory
    /// @return immutable module paths mapped to Base64-encoded verified bytes
    /// @throws IOException if an inventoried module changes or the graph exceeds its limits
    static @Unmodifiable Map<String, String> readVerifiedJavaScriptModules(
            PluginManifest manifest,
            VerifiedPluginPackage pluginPackage
    ) throws IOException {
        Map<String, String> modules = new LinkedHashMap<>();
        long retainedBytes = 0;
        for (Path relative : pluginPackage.getRelativeFiles()) {
            String resource = relative.toString().replace('\\', '/');
            String lowerCaseName = relative.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!lowerCaseName.endsWith(".js")
                    && !lowerCaseName.endsWith(".cjs")
                    && !lowerCaseName.endsWith(".json")) {
                continue;
            }
            if (modules.size() >= MAX_MODULE_FILES) {
                throw new IOException("JavaScript plugin contains more than " + MAX_MODULE_FILES + " modules");
            }
            byte @Nullable @Unmodifiable [] bytes = pluginPackage.readResourceBytes(resource);
            if (bytes == null) {
                throw new IOException("Verified JavaScript module is missing: " + resource);
            }
            if (bytes.length > MAX_MODULE_BYTES || retainedBytes > MAX_MODULE_BYTES - bytes.length) {
                throw new IOException("JavaScript plugin module graph exceeds " + MAX_MODULE_BYTES + " bytes");
            }
            retainedBytes += bytes.length;
            modules.put(resource, Base64.getEncoder().encodeToString(bytes));
        }
        if (!modules.containsKey(manifest.getEntrypoint())) {
            throw new IOException("JavaScript entry point is not a verified CommonJS module: "
                    + manifest.getEntrypoint());
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(modules));
    }

    /// Captures the exact loose entry script bytes verified for one JavaScript artifact.
    ///
    /// @param manifest validated JavaScript manifest
    /// @param pluginPackage exact verified package inventory
    /// @return private verified script bytes
    /// @throws IOException if the entry path is absent, unsafe, nested in a JAR, or changed during the read
    static byte @Unmodifiable [] readVerifiedEntryScript(
            PluginManifest manifest,
            VerifiedPluginPackage pluginPackage
    ) throws IOException {
        String entrypoint = manifest.getEntrypoint();
        if (!pluginPackage.containsLooseFile(entrypoint)) {
            throw new IOException("JavaScript entry point is not a verified loose package file: " + entrypoint);
        }
        byte @Nullable @Unmodifiable [] entryScript = pluginPackage.readResourceBytes(entrypoint);
        if (entryScript == null) {
            throw new IOException("JavaScript entry point is missing from the verified package: " + entrypoint);
        }
        return entryScript;
    }

    /// Builds a Node command that consumes the verified entry script from standard input.
    ///
    /// VM modules are enabled solely for synthetic namespaces returned by the verified dynamic-import resolver.
    ///
    /// @param nodeExecutable managed Node.js executable
    /// @param event lifecycle event name
    /// @return immutable command arguments
    static @Unmodifiable List<String> createLifecycleCommand(Path nodeExecutable, String event) {
        return List.of(
                nodeExecutable.toString(),
                "--disable-warning=ExperimentalWarning",
                "--experimental-vm-modules",
                "-",
                event
        );
    }

    /// Drains one lifecycle output stream while retaining only a bounded prefix for logging and protocol parsing.
    ///
    /// Continuing to drain discarded bytes prevents a verbose child process from blocking on a full pipe.
    ///
    /// @param input child-process combined output stream
    /// @param output bounded retained prefix
    /// @return whether any output bytes were discarded
    /// @throws IOException if the process stream cannot be read
    static boolean copyBoundedLifecycleOutput(
            InputStream input,
            ByteArrayOutputStream output
    ) throws IOException {
        byte[] buffer = new byte[8192];
        boolean truncated = output.size() > MAX_LIFECYCLE_OUTPUT_BYTES;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            int remaining = Math.max(0, MAX_LIFECYCLE_OUTPUT_BYTES - output.size());
            int retained = Math.min(read, remaining);
            if (retained > 0) {
                output.write(buffer, 0, retained);
            }
            if (retained < read) {
                truncated = true;
            }
        }
        return truncated;
    }

    /// Executes JavaScript lifecycle events as separate managed Node.js subprocesses.
    ///
    /// Package metadata, declared permissions, private storage, and launcher version are exposed through
    /// `HMCL_PLUGIN_*` environment variables for each invocation. The extracted package path is never exposed.
    @NotNullByDefault
    private static final class NodeJSPlugin implements Plugin {
        /// Maximum duration allowed for one lifecycle process.
        private static final int LIFECYCLE_TIMEOUT_SECONDS = 30;

        /// Prefix identifying one JSON UI protocol message in process output.
        private static final String MESSAGE_PREFIX = "HMCL_PLUGIN_MESSAGE:";

        /// Validated package manifest.
        private final PluginManifest manifest;

        /// Immutable self-contained lifecycle script captured from the complete verified module graph.
        private final byte @Unmodifiable [] lifecycleScript;

        /// Serial executor preventing overlapping lifecycle processes for this plugin.
        private final ExecutorService executor;

        /// Runtime context assigned during `onLoad`, or `null` before lifecycle registration.
        private @Nullable PluginContext context;

        /// Whether UI responses may still be registered for this plugin.
        private volatile boolean enabled;

        /// Creates one managed Node.js lifecycle implementation.
        ///
        /// @param manifest validated plugin manifest
        /// @param lifecycleScript verified self-contained lifecycle script bytes
        private NodeJSPlugin(
                PluginManifest manifest,
                byte @Unmodifiable [] lifecycleScript
        ) {
            this.manifest = manifest;
            this.lifecycleScript = lifecycleScript.clone();
            this.executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "hmcl-js-plugin-" + manifest.getId());
                thread.setDaemon(true);
                return thread;
            });
        }

        /// Runs one lifecycle event without additional event-specific environment variables.
        ///
        /// @param event lifecycle event name
        /// @return trimmed combined process output, or `null` when execution fails
        private @Nullable String runLifecycleEvent(String event) {
            return runLifecycleEvent(event, Collections.emptyMap());
        }

        /// Runs one lifecycle event and captures its combined standard output and error stream.
        ///
        /// @param event lifecycle event name
        /// @param additionalEnvironment event-specific environment variables
        /// @return trimmed combined process output, or `null` when execution fails
        private @Nullable String runLifecycleEvent(
                String event,
                @Unmodifiable Map<String, String> additionalEnvironment
        ) {
            @Nullable Path nodeExe = NodeJSManager.getNodeExecutable();
            if (nodeExe == null) {
                LOG.warning("Managed Node.js runtime missing; cannot run " + event + " for " + manifest.getId());
                return null;
            }

            @Nullable PluginContext activeContext = context;
            if (activeContext == null) {
                LOG.warning("JavaScript plugin context missing; cannot run " + event + " for " + manifest.getId());
                return null;
            }

            ProcessBuilder pb = new ProcessBuilder(createLifecycleCommand(nodeExe, event));
            pb.redirectErrorStream(true);

            pb.environment().put("HMCL_PLUGIN_EVENT", event);
            pb.environment().put("HMCL_PLUGIN_ID", manifest.getId());
            pb.environment().put("HMCL_PLUGIN_NAME", manifest.getName());
            pb.environment().put("HMCL_PLUGIN_VERSION", manifest.getVersion());
            pb.environment().remove("HMCL_PLUGIN_DIR");
            pb.environment().put("HMCL_PLUGIN_PERMISSIONS", JsonUtils.GSON.toJson(
                    manifest.getPermissions().stream().map(PluginPermission::getId).toList()
            ));
            pb.environment().put("HMCL_PLUGIN_DATA_DIR", activeContext.getDataDirectory().toString());
            pb.environment().put("HMCL_VERSION", activeContext.getLauncherVersion());
            pb.environment().putAll(additionalEnvironment);

            try {
                Files.createDirectories(activeContext.getDataDirectory());
                pb.directory(activeContext.getDataDirectory().toFile());
                Process process = pb.start();
                ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
                AtomicBoolean outputTruncated = new AtomicBoolean();
                Thread outputReader = new Thread(() -> {
                    try (var in = process.getInputStream()) {
                        outputTruncated.set(copyBoundedLifecycleOutput(in, outputBuffer));
                    } catch (IOException exception) {
                        LOG.warning("Failed to read JS plugin output for " + manifest.getId(), exception);
                    }
                }, "hmcl-js-output-" + manifest.getId());
                outputReader.setDaemon(true);
                outputReader.start();

                try (var scriptInput = process.getOutputStream()) {
                    scriptInput.write(lifecycleScript);
                }

                boolean finished = process.waitFor(LIFECYCLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                    LOG.warning("JS plugin " + manifest.getId() + " " + event + " timed out after "
                            + LIFECYCLE_TIMEOUT_SECONDS + "s");
                    return null;
                }
                outputReader.join(1000);
                if (outputReader.isAlive()) {
                    process.getInputStream().close();
                    outputReader.join(1000);
                }
                if (outputReader.isAlive()) {
                    LOG.warning("JS plugin output reader did not stop for " + manifest.getId() + " " + event);
                    return null;
                }
                String output = outputBuffer.toString(StandardCharsets.UTF_8).trim();
                if (outputTruncated.get()) {
                    LOG.warning("JS plugin " + manifest.getId() + " " + event + " output exceeded "
                            + MAX_LIFECYCLE_OUTPUT_BYTES + " bytes and was truncated");
                }
                if (!output.isEmpty()) {
                    LOG.info("[JS:" + manifest.getId() + ":" + event + "] " + output);
                }
                if (process.exitValue() != 0) {
                    LOG.warning("JS plugin " + manifest.getId() + " " + event
                            + " exited with code " + process.exitValue());
                }
                return output;
            } catch (IOException exception) {
                LOG.error("Failed to run JS plugin " + manifest.getId() + " " + event, exception);
                return null;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                LOG.warning("Interrupted while running JS plugin " + manifest.getId() + " " + event);
                return null;
            }
        }

        /// Stores the runtime context and schedules the JavaScript `onLoad` event.
        ///
        /// @param context plugin runtime context
        @Override
        public void onLoad(PluginContext context) {
            this.context = context;
            submit(() -> runLifecycleEvent("onLoad"));
        }

        /// Marks the plugin active and schedules UI registration from the JavaScript `onEnable` response.
        @Override
        public void onEnable() {
            enabled = true;
            submit(() -> {
                @Nullable JsonObject message = parseProtocolMessage(runLifecycleEvent("onEnable"));
                if (message != null && enabled) {
                    Platform.runLater(() -> {
                        if (enabled) {
                            registerUI(message);
                        }
                    });
                }
            });
        }

        /// Returns the last valid `hmcl-ui-v1` protocol message found in lifecycle output.
        ///
        /// @param output lifecycle process output or `null`
        /// @return parsed protocol message or `null`
        private @Nullable JsonObject parseProtocolMessage(@Nullable String output) {
            if (output == null || output.isBlank()) {
                return null;
            }

            @Nullable JsonObject result = null;
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
                } catch (RuntimeException exception) {
                    LOG.warning("Ignoring malformed JS plugin message from " + manifest.getId(), exception);
                }
            }
            return result;
        }

        /// Registers the restricted sidebar UI described by one validated protocol message.
        ///
        /// @param message protocol message
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
                PluginContext activeContext = Objects.requireNonNull(context, "JavaScript plugin is not loaded");
                activeContext.registerJavaScriptSidebarItem(
                        title,
                        sidebar.getAsJsonObject("page"),
                        this::handleUIEvent
                );
                LOG.info("JS plugin " + manifest.getId() + " registered JavaFX sidebar page: " + title);
            } catch (RuntimeException exception) {
                LOG.warning("Failed to register JS plugin UI for " + manifest.getId(), exception);
            }
        }

        /// Runs one JavaScript UI event and returns its optional protocol response on the JavaFX thread.
        ///
        /// @param eventId declarative UI event ID
        /// @param values current declarative control values
        /// @param callback JavaFX-thread response consumer
        private void handleUIEvent(
                String eventId,
                Map<String, String> values,
                Consumer<@Nullable JsonObject> callback
        ) {
            if (!enabled) {
                Platform.runLater(() -> callback.accept(null));
                return;
            }

            submit(() -> {
                @Unmodifiable Map<String, String> environment = Map.of(
                        "HMCL_UI_EVENT_ID", eventId,
                        "HMCL_UI_VALUES", JsonUtils.GSON.toJson(values)
                );
                @Nullable JsonObject response = parseProtocolMessage(runLifecycleEvent("onUiEvent", environment));
                Platform.runLater(() -> callback.accept(enabled ? response : null));
            });
        }

        /// Schedules one lifecycle action unless shutdown has already rejected new work.
        ///
        /// @param action lifecycle action
        private void submit(Runnable action) {
            try {
                executor.execute(action);
            } catch (RejectedExecutionException exception) {
                LOG.warning("JS plugin executor is already closed: " + manifest.getId());
            }
        }

        /// Marks the plugin inactive and schedules the JavaScript `onDisable` event.
        @Override
        public void onDisable() {
            enabled = false;
            submit(() -> runLifecycleEvent("onDisable"));
        }

        /// Schedules the JavaScript `onUnload` event and prevents later lifecycle submissions.
        @Override
        public void onUnload() {
            enabled = false;
            submit(() -> runLifecycleEvent("onUnload"));
            executor.shutdown();
        }

        /// Returns the validated plugin manifest.
        ///
        /// @return plugin manifest
        @Override
        public PluginManifest getManifest() {
            return manifest;
        }
    }
}

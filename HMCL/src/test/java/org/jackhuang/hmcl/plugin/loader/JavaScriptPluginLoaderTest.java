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
package org.jackhuang.hmcl.plugin.loader;

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.internal.PluginPackageVersions;
import org.jackhuang.hmcl.plugin.internal.VerifiedPluginPackage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies standard Node module resolution against a complete captured graph.
///
/// Node built-ins and direct reads from already-known absolute paths are outside this module-integrity boundary.
@NotNullByDefault
public final class JavaScriptPluginLoaderTest {
    /// Keeps the captured entry immutable and rejects a later read after the extracted cache is replaced.
    ///
    /// @param temporaryDirectory isolated package and cache root
    /// @throws Exception if package preparation or mutation fails
    @Test
    public void keepVerifiedEntrySnapshotAfterCacheReplacement(@TempDir Path temporaryDirectory) throws Exception {
        byte @Unmodifiable [] originalScript = "console.log('verified');\n".getBytes(StandardCharsets.UTF_8);
        PluginManifest manifest = createManifest();
        VerifiedPluginPackage pluginPackage = preparePackage(
                temporaryDirectory,
                manifest,
                originalScript
        );

        byte @Unmodifiable [] captured = JavaScriptPluginLoader.readVerifiedEntryScript(manifest, pluginPackage);
        Files.writeString(
                pluginPackage.getDirectory().resolve(manifest.getEntrypoint()),
                "console.log('replaced');\n",
                StandardCharsets.UTF_8
        );

        assertArrayEquals(originalScript, captured);
        assertThrows(
                IOException.class,
                () -> JavaScriptPluginLoader.readVerifiedEntryScript(manifest, pluginPackage)
        );
    }

    /// Keeps relative helper modules bound to verified bytes after the extracted cache is replaced.
    ///
    /// @param temporaryDirectory isolated package and cache root
    /// @throws Exception if package preparation or mutation fails
    @Test
    public void keepVerifiedModuleGraphAfterCacheReplacement(@TempDir Path temporaryDirectory) throws Exception {
        byte @Unmodifiable [] entryScript = "require('./helper');\n".getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] originalHelper = "module.exports = 'verified';\n".getBytes(StandardCharsets.UTF_8);
        PluginManifest manifest = createManifest();
        VerifiedPluginPackage pluginPackage = preparePackage(
                temporaryDirectory,
                manifest,
                entryScript,
                Map.of("helper.js", originalHelper)
        );

        @Unmodifiable Map<String, String> captured =
                JavaScriptPluginLoader.readVerifiedJavaScriptModules(manifest, pluginPackage);
        Files.writeString(
                pluginPackage.getDirectory().resolve("helper.js"),
                "module.exports = 'replaced';\n",
                StandardCharsets.UTF_8
        );

        assertEquals(Base64.getEncoder().encodeToString(originalHelper), captured.get("helper.js"));
        assertThrows(
                IOException.class,
                () -> JavaScriptPluginLoader.readVerifiedJavaScriptModules(manifest, pluginPackage)
        );
    }

    /// Omits the extracted cache path and replaces it with a synthetic NUL-prefixed module root.
    ///
    /// @param temporaryDirectory isolated package and cache root
    /// @throws Exception if package preparation or bootstrap generation fails
    @Test
    public void hideExtractedPackagePathFromNodeBootstrap(@TempDir Path temporaryDirectory) throws Exception {
        PluginManifest manifest = createManifest();
        VerifiedPluginPackage pluginPackage = preparePackage(
                temporaryDirectory,
                manifest,
                "console.log('verified');\n".getBytes(StandardCharsets.UTF_8)
        );

        String bootstrap = new String(
                JavaScriptPluginLoader.createVerifiedLifecycleScript(manifest, pluginPackage),
                StandardCharsets.UTF_8
        );

        assertFalse(bootstrap.contains(pluginPackage.getDirectory().toString()));
        assertFalse(bootstrap.contains("process.env.HMCL_PLUGIN_DIR"));
        assertTrue(bootstrap.contains("__hmclPath.parse(process.cwd()).root"));
        assertTrue(bootstrap.contains("'\\0hmcl-plugin'"));
    }

    /// Resolves `Module.createRequire(__filename)` relative to the nested virtual module that created it.
    ///
    /// @param temporaryDirectory isolated package, cache, and process working root
    /// @throws Exception if package preparation or lifecycle execution fails
    @Test
    public void resolveNestedCreateRequireAgainstVirtualParent(@TempDir Path temporaryDirectory) throws Exception {
        Assumptions.assumeTrue(isNode24OrNewerAvailable(), "Node.js 24 or newer is unavailable on PATH");
        byte @Unmodifiable [] entryScript = "require('./sub/module.js');\n"
                .getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] nestedModule = """
                const Module = require('module');
                const nestedRequire = Module.createRequire(__filename);
                console.log('nestedCreateRequire=' + nestedRequire('./helper.js'));
                """.getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] nestedHelper = "module.exports = 'verified-nested';\n"
                .getBytes(StandardCharsets.UTF_8);
        PluginManifest manifest = createManifest();
        VerifiedPluginPackage pluginPackage = preparePackage(
                temporaryDirectory,
                manifest,
                entryScript,
                Map.of(
                        "helper.js", "module.exports = 'wrong-root';\n".getBytes(StandardCharsets.UTF_8),
                        "sub/module.js", nestedModule,
                        "sub/helper.js", nestedHelper
                )
        );
        byte @Unmodifiable [] lifecycleScript =
                JavaScriptPluginLoader.createVerifiedLifecycleScript(manifest, pluginPackage);
        Files.writeString(
                pluginPackage.getDirectory().resolve("sub/helper.js"),
                "module.exports = 'cache-replaced';\n",
                StandardCharsets.UTF_8
        );
        Path workingDirectory = Files.createDirectory(temporaryDirectory.resolve("working"));
        Files.writeString(
                workingDirectory.resolve("helper.js"),
                "module.exports = 'working-replaced';\n",
                StandardCharsets.UTF_8
        );
        ProcessBuilder builder = new ProcessBuilder(
                JavaScriptPluginLoader.createLifecycleCommand(Path.of("node"), "onEnable")
        );
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.environment().remove("HMCL_PLUGIN_DIR");

        Process process = builder.start();
        try (var input = process.getOutputStream()) {
            input.write(lifecycleScript);
        }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Node lifecycle process timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("nestedCreateRequire=verified-nested"), output);
        assertFalse(output.contains("wrong-root"), output);
        assertFalse(output.contains("cache-replaced"), output);
        assertFalse(output.contains("working-replaced"), output);
    }

    /// Executes every supported module entry through the captured graph after mutable files are replaced.
    ///
    /// The regression covers standard CommonJS `require`, dynamic `import()`, global require, `Module.createRequire`,
    /// direct reads through synthetic filenames, VM imports, and `Module.prototype.load`. It does not model an OS
    /// sandbox or restrict Node built-ins from reading an independently known absolute path.
    ///
    /// @param temporaryDirectory isolated package, cache, and process working root
    /// @throws Exception if package preparation or lifecycle execution fails
    @Test
    public void executeCapturedModulesInsteadOfMutableFiles(@TempDir Path temporaryDirectory) throws Exception {
        Assumptions.assumeTrue(isNode24OrNewerAvailable(), "Node.js 24 or newer is unavailable on PATH");
        byte @Unmodifiable [] entryScript = """
                const Module = require('module');
                const fs = require('fs');
                const path = require('path');
                const vm = require('vm');

                (async function() {
                    console.log('require=' + require('./helper'));
                    console.log('dynamic=' + (await import('./helper.js')).default);
                    const workingRequire = Module.createRequire(path.join(process.cwd(), 'attacker.js'));
                    console.log('createRequire=' + workingRequire('./helper.js'));
                    console.log('globalRequire=' + global.require('./helper.js'));
                    console.log('cacheEnv=' + String(process.env.HMCL_PLUGIN_DIR));

                    for (const [label, candidate] of [
                        ['dirname', path.join(__dirname, 'helper.js')],
                        ['resolved', workingRequire.resolve('./helper.js')]
                    ]) {
                        try {
                            console.log(label + 'Fs=' + fs.readFileSync(candidate, 'utf8').trim());
                        } catch (error) {
                            console.log(label + 'Fs=blocked');
                        }
                    }

                    try {
                        const diskModule = new Module('disk-module');
                        diskModule.load(path.join(process.cwd(), 'helper.js'));
                        console.log('moduleLoad=' + diskModule.exports);
                    } catch (error) {
                        console.log('moduleLoad=blocked');
                    }

                    try {
                        const generated = new vm.Script("import('./helper.js')", {
                            filename: path.join(process.cwd(), 'generated.js'),
                            importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
                        });
                        console.log('vmDynamic=' + (await generated.runInThisContext()).default);
                    } catch (error) {
                        console.log('vmDynamic=blocked');
                    }
                })().catch(function(error) {
                    console.error(error.stack || error);
                    process.exitCode = 1;
                });
                """.getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] originalHelper = "module.exports = 'verified';\n"
                .getBytes(StandardCharsets.UTF_8);
        PluginManifest manifest = createManifest();
        VerifiedPluginPackage pluginPackage = preparePackage(
                temporaryDirectory,
                manifest,
                entryScript,
                Map.of("helper.js", originalHelper)
        );
        byte @Unmodifiable [] lifecycleScript =
                JavaScriptPluginLoader.createVerifiedLifecycleScript(manifest, pluginPackage);
        Files.writeString(
                pluginPackage.getDirectory().resolve("helper.js"),
                "module.exports = 'cache-replaced';\n",
                StandardCharsets.UTF_8
        );
        Path workingDirectory = Files.createDirectory(temporaryDirectory.resolve("working"));
        Files.writeString(
                workingDirectory.resolve("helper.js"),
                "module.exports = 'working-replaced';\n",
                StandardCharsets.UTF_8
        );
        ProcessBuilder builder = new ProcessBuilder(
                JavaScriptPluginLoader.createLifecycleCommand(Path.of("node"), "onEnable")
        );
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.environment().remove("HMCL_PLUGIN_DIR");

        Process process = builder.start();
        try (var input = process.getOutputStream()) {
            input.write(lifecycleScript);
        }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Node lifecycle process timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("require=verified"), output);
        assertTrue(output.contains("dynamic=verified"), output);
        assertTrue(output.contains("createRequire=verified"), output);
        assertTrue(output.contains("globalRequire=verified"), output);
        assertTrue(output.contains("cacheEnv=undefined"), output);
        assertTrue(output.contains("dirnameFs=blocked"), output);
        assertTrue(output.contains("resolvedFs=blocked"), output);
        assertTrue(output.contains("moduleLoad=blocked"), output);
        assertTrue(output.contains("vmDynamic=blocked"), output);
        assertFalse(output.contains("cache-replaced"), output);
        assertFalse(output.contains("working-replaced"), output);
        assertFalse(
                new String(lifecycleScript, StandardCharsets.UTF_8)
                        .contains(pluginPackage.getDirectory().toString()),
                "Lifecycle bootstrap exposed the extracted package path"
        );
    }

    /// Uses Node's standard-input marker instead of exposing a mutable cache script path in the command.
    @Test
    public void executeLifecycleScriptFromStandardInput() {
        Path nodeExecutable = Path.of("managed-node");

        assertEquals(
                List.of(
                        nodeExecutable.toString(),
                        "--disable-warning=ExperimentalWarning",
                        "--experimental-vm-modules",
                        "-",
                        "onEnable"
                ),
                JavaScriptPluginLoader.createLifecycleCommand(nodeExecutable, "onEnable")
        );
    }

    /// Drains arbitrary child output while retaining at most the one-MiB lifecycle prefix.
    ///
    /// @throws IOException if the in-memory stream cannot be read
    @Test
    public void boundLifecycleProcessOutput() throws IOException {
        byte @Unmodifiable [] verboseOutput = new byte[1024 * 1024 + 8192];
        ByteArrayOutputStream retained = new ByteArrayOutputStream();

        boolean truncated = JavaScriptPluginLoader.copyBoundedLifecycleOutput(
                new ByteArrayInputStream(verboseOutput),
                retained
        );

        assertTrue(truncated);
        assertEquals(1024 * 1024, retained.size());
    }

    /// Creates the validated JavaScript manifest shared by the package fixture.
    ///
    /// @return validated schema-v3 JavaScript manifest
    /// @throws IOException if the fixture manifest is rejected
    private static PluginManifest createManifest() throws IOException {
        return PluginManifest.fromJson(new StringReader(manifestJson()));
    }

    /// Returns the package manifest JSON shared by parsing and archive creation.
    ///
    /// @return schema-v3 JavaScript manifest JSON
    private static String manifestJson() {
        return """
                {
                  "schemaVersion": 3,
                  "id": "dev.hmclnex.test.javascript-snapshot",
                  "name": "JavaScript Snapshot Test",
                  "version": "1.0.0",
                  "type": "javascript",
                  "entrypoint": "main.js",
                  "permissions": []
                }
                """;
    }

    /// Returns whether Node.js 24 or newer can start from the current test PATH.
    ///
    /// @return whether a compatible Node.js runtime starts and reports its version
    private static boolean isNode24OrNewerAvailable() {
        try {
            Process process = new ProcessBuilder("node", "--version")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return false;
            }
            String version = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int firstDot = version.indexOf('.');
            if (!version.startsWith("v") || firstDot < 2) {
                return false;
            }
            return Integer.parseInt(version.substring(1, firstDot)) >= 24;
        } catch (IOException exception) {
            return false;
        } catch (NumberFormatException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /// Creates and verifies one JavaScript package fixture.
    ///
    /// @param temporaryDirectory isolated package and cache root
    /// @param manifest validated package manifest
    /// @param script JavaScript entry bytes
    /// @return verified extracted package
    /// @throws IOException if packaging or verification fails
    private static VerifiedPluginPackage preparePackage(
            Path temporaryDirectory,
            PluginManifest manifest,
            byte @Unmodifiable [] script
    ) throws IOException {
        return preparePackage(temporaryDirectory, manifest, script, Map.of());
    }

    /// Creates and verifies one JavaScript package fixture with additional loose modules.
    ///
    /// @param temporaryDirectory isolated package and cache root
    /// @param manifest validated package manifest
    /// @param script JavaScript entry bytes
    /// @param additionalFiles additional package-relative file bytes
    /// @return verified extracted package
    /// @throws IOException if packaging or verification fails
    private static VerifiedPluginPackage preparePackage(
            Path temporaryDirectory,
            PluginManifest manifest,
            byte @Unmodifiable [] script,
            @Unmodifiable Map<String, byte @Unmodifiable []> additionalFiles
    ) throws IOException {
        Path nplFile = temporaryDirectory.resolve("javascript-snapshot.npl");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(nplFile))) {
            writeZipEntry(output, "plugin.json", manifestJson().getBytes(StandardCharsets.UTF_8));
            writeZipEntry(output, manifest.getEntrypoint(), script);
            for (Map.Entry<String, byte @Unmodifiable []> entry : additionalFiles.entrySet()) {
                writeZipEntry(output, entry.getKey(), entry.getValue());
            }
        }
        PluginArtifactIdentity identity = new PluginArtifactIdentity(
                manifest.getId(),
                manifest.getVersion(),
                PluginPackageVersions.calculateSha256(nplFile)
        );
        return PluginPackageVersions.prepareVerifiedLifecyclePackage(
                nplFile,
                temporaryDirectory.resolve("plugin-data"),
                identity
        );
    }

    /// Writes one deterministic NPL entry.
    ///
    /// @param output open package output
    /// @param name package-relative entry name
    /// @param bytes entry payload
    /// @throws IOException if the entry cannot be written
    private static void writeZipEntry(
            ZipOutputStream output,
            String name,
            byte @Unmodifiable [] bytes
    ) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }
}

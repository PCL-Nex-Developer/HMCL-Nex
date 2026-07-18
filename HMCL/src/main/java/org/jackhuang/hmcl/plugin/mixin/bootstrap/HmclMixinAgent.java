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
package org.jackhuang.hmcl.plugin.mixin.bootstrap;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/// Installs SpongePowered Mixin as an instrumentation transformer before HMCL application classes load.
@NotNullByDefault
public final class HmclMixinAgent {
    /// Generated JAR name used to expose files stored directly at an extracted plugin root.
    static final String ROOT_RESOURCE_JAR = ".hmcl-agent-root.jar";

    /// Plugin JAR handles retained because instrumentation may continue reading them for the process lifetime.
    private static final List<JarFile> OPEN_PLUGIN_JARS = new ArrayList<>();

    /// Prevents construction of the premain agent utility.
    private HmclMixinAgent() {
    }

    /// Initializes plugin class paths, Mixin configuration, and the HMCL class-file transformer.
    ///
    /// @param agentArguments optional `-javaagent` argument text
    /// @param instrumentation active JVM instrumentation handle
    public static void premain(@Nullable String agentArguments, Instrumentation instrumentation) {
        System.setProperty(HmclMixinBootstrap.AGENT_ACTIVE_PROPERTY, "true");
        if (Boolean.getBoolean(HmclMixinBootstrap.DISABLE_PROPERTY)) {
            return;
        }

        try {
            HmclMixinBootstrap.AgentConfiguration configuration = HmclMixinBootstrap.prepareAgentConfiguration();
            if (configuration.mixinConfigs().isEmpty()) {
                return;
            }

            appendPluginClassPath(configuration.classPathEntries(), instrumentation);
            ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
            HmclMixinService.configure(systemClassLoader, instrumentation);

            MixinBootstrap.init();
            MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
            Mixins.addConfigurations(configuration.mixinConfigs().toArray(String[]::new), null);

            HmclMixinService service = (HmclMixinService) MixinService.getService();
            IMixinTransformer transformer = service.createTransformer();
            enterDefaultPhase();
            instrumentation.addTransformer(new HmclClassFileTransformer(transformer), false);

            System.setProperty(
                    HmclMixinBootstrap.ACTIVE_PROPERTY,
                    String.join(",", configuration.activePluginIds())
            );
            report("Enabled " + configuration.mixinConfigs().size() + " Mixin configuration(s) from "
                    + configuration.activePluginIds().size() + " plugin(s)");
        } catch (IOException | ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to initialize the HMCL plugin Mixin agent", exception);
        }
    }

    /// Appends plugin root resources and nested JAR files to the system class loader search path.
    ///
    /// @param entries extracted roots and JAR files
    /// @param instrumentation active instrumentation handle
    /// @throws IOException if a resource JAR cannot be created or opened
    private static void appendPluginClassPath(
            List<Path> entries,
            Instrumentation instrumentation
    ) throws IOException {
        for (Path entry : entries) {
            Path jarPath = Files.isDirectory(entry) ? createRootResourceJar(entry) : entry;
            JarFile jarFile = new JarFile(jarPath.toFile());
            OPEN_PLUGIN_JARS.add(jarFile);
            instrumentation.appendToSystemClassLoaderSearch(jarFile);
        }
    }

    /// Packages non-JAR files stored at an extracted plugin root into a system-searchable resource JAR.
    ///
    /// @param pluginRoot extracted plugin root
    /// @return generated resource JAR
    /// @throws IOException if traversal or JAR creation fails
    private static Path createRootResourceJar(Path pluginRoot) throws IOException {
        Path output = pluginRoot.resolve(ROOT_RESOURCE_JAR);
        if (Files.isRegularFile(output)) {
            return output;
        }

        Path temporary = output.resolveSibling(ROOT_RESOURCE_JAR + ".tmp");
        Files.deleteIfExists(temporary);
        try (JarOutputStream jarOutput = new JarOutputStream(
                new BufferedOutputStream(Files.newOutputStream(temporary))
        ); Stream<Path> files = Files.walk(pluginRoot)) {
            for (Path file : files
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.equals(output) && !path.equals(temporary))
                    .filter(path -> !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .toList()) {
                String entryName = pluginRoot.relativize(file).toString().replace('\\', '/');
                JarEntry jarEntry = new JarEntry(entryName);
                jarEntry.setTime(0);
                jarOutput.putNextEntry(jarEntry);
                try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(file))) {
                    input.transferTo(jarOutput);
                }
                jarOutput.closeEntry();
            }
        }

        try {
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return output;
    }

    /// Advances the fixed Mixin 0.8.7 environment from `PREINIT` to `DEFAULT` before HMCL loads.
    ///
    /// @throws ReflectiveOperationException if the bundled host hook changes
    private static void enterDefaultPhase() throws ReflectiveOperationException {
        Method gotoPhase = MixinEnvironment.class.getDeclaredMethod(
                "gotoPhase",
                MixinEnvironment.Phase.class
        );
        gotoPhase.setAccessible(true);
        try {
            gotoPhase.invoke(null, MixinEnvironment.Phase.DEFAULT);
        } catch (InvocationTargetException exception) {
            @Nullable Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw exception;
        }
    }

    /// Prints an agent diagnostic before the regular HMCL logger is initialized.
    ///
    /// @param message diagnostic text
    private static void report(String message) {
        System.err.println("[HMCL Plugin Mixin] " + message);
    }

    /// Applies Mixin only to HMCL application classes loaded by the system loader.
    @NotNullByDefault
    private static final class HmclClassFileTransformer implements ClassFileTransformer {
        /// Internal Mixin transformer configured during premain.
        private final IMixinTransformer transformer;

        /// Creates an instrumentation bridge for one active Mixin transformer.
        ///
        /// @param transformer active Mixin transformer
        private HmclClassFileTransformer(IMixinTransformer transformer) {
            this.transformer = transformer;
        }

        /// Applies Mixin before an HMCL class is defined.
        ///
        /// @param module defining module
        /// @param loader defining loader, or `null` for bootstrap classes
        /// @param className internal class name, or `null`
        /// @param classBeingRedefined class being redefined, or `null` for first definition
        /// @param protectionDomain target protection domain
        /// @param classFileBuffer original bytecode
        /// @return transformed bytecode or `null` when unchanged
        /// @throws IllegalClassFormatException if Mixin rejects the target class
        @Override
        public byte @Nullable [] transform(
                @Nullable Module module,
                @Nullable ClassLoader loader,
                @Nullable String className,
                @Nullable Class<?> classBeingRedefined,
                @Nullable ProtectionDomain protectionDomain,
                byte[] classFileBuffer
        ) throws IllegalClassFormatException {
            if (loader == null
                    || className == null
                    || classBeingRedefined != null
                    || !className.startsWith("org/jackhuang/hmcl/")
                    || className.startsWith("org/jackhuang/hmcl/plugin/mixin/bootstrap/")) {
                return null;
            }

            String binaryName = className.replace('/', '.');
            try {
                byte[] transformed = transformer.transformClassBytes(binaryName, binaryName, classFileBuffer);
                return transformed == classFileBuffer ? null : transformed;
            } catch (Throwable throwable) {
                IllegalClassFormatException exception = new IllegalClassFormatException(
                        "Mixin failed for " + binaryName + ": " + throwable.getMessage()
                );
                exception.initCause(throwable);
                throw exception;
            }
        }
    }
}

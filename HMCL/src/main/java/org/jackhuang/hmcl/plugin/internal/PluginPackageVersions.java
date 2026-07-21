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
package org.jackhuang.hmcl.plugin.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/// Publishes extracted plugin packages into immutable content-addressed directories.
///
/// Complete versions are never overwritten or removed during normal loading, so another HMCL
/// process may continue reading an older version without blocking a new package from starting.
@NotNullByDefault
public final class PluginPackageVersions {
    /// Reserved directory containing immutable package versions below a plugin package root.
    public static final String VERSIONS_DIRECTORY = ".versions";

    /// Generated JAR containing resources and loose classes stored at a Mixin plugin package root.
    public static final String ROOT_RESOURCE_JAR = ".hmcl-agent-root.jar";

    /// Root manifest entry required in every plugin package.
    private static final String PLUGIN_MANIFEST = "plugin.json";

    /// Marker written only after a version has been completely prepared.
    private static final String COMPLETION_MARKER = ".hmcl-complete";

    /// Current immutable package layout version stored in the completion marker.
    private static final String LAYOUT_VERSION = "1";

    /// Maximum number of archive entries accepted from one plugin package.
    private static final int MAX_ARCHIVE_ENTRIES = 10_000;

    /// Maximum aggregate uncompressed bytes accepted from one plugin package.
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L;

    /// Plugin identifiers accepted as safe content-addressed directory names.
    private static final Pattern PLUGIN_ID_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{1,127}");

    /// JVM-local monitors preventing overlapping file locks for the same version in one process.
    private static final Map<Path, Object> LOCAL_LOCKS = new ConcurrentHashMap<>();

    /// Prepares a package version used by the regular plugin lifecycle loader.
    ///
    /// @param nplFile source plugin package
    /// @param packageRoot root such as `plugin-data`
    /// @param pluginId validated plugin identifier
    /// @return immutable extracted package directory
    /// @throws IOException if hashing, extraction, validation, or publication fails
    public static Path prepareLifecyclePackage(Path nplFile, Path packageRoot, String pluginId) throws IOException {
        return preparePackage(nplFile, packageRoot, pluginId, false);
    }

    /// Prepares a package version used by the startup Mixin agent.
    ///
    /// A root-resource JAR is generated before publication, ensuring the agent never writes into
    /// a version directory after another process may have begun using it.
    ///
    /// @param nplFile source plugin package
    /// @param packageRoot root such as `plugin-cache`
    /// @param pluginId validated plugin identifier
    /// @return immutable extracted package directory
    /// @throws IOException if hashing, extraction, validation, root-JAR generation, or publication fails
    public static Path prepareMixinPackage(Path nplFile, Path packageRoot, String pluginId) throws IOException {
        return preparePackage(nplFile, packageRoot, pluginId, true);
    }

    /// Returns the directory containing all immutable versions for one plugin.
    ///
    /// @param packageRoot plugin package or cache root
    /// @param pluginId validated plugin identifier
    /// @return version container directory
    public static Path getPluginVersionsDirectory(Path packageRoot, String pluginId) {
        if (!PLUGIN_ID_PATTERN.matcher(pluginId).matches()) {
            throw new IllegalArgumentException("Invalid plugin ID: " + pluginId);
        }
        return packageRoot.resolve(VERSIONS_DIRECTORY).resolve(pluginId);
    }

    /// Calculates the lower-case SHA-256 digest of a plugin package.
    ///
    /// @param file package file
    /// @return lower-case hexadecimal digest
    /// @throws IOException if the file cannot be read or SHA-256 is unavailable
    public static String calculateSha256(Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }

        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }

        StringBuilder result = new StringBuilder(digest.getDigestLength() * 2);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    /// Prepares and publishes one immutable content-addressed package version.
    ///
    /// @param nplFile source plugin package
    /// @param packageRoot package root
    /// @param pluginId validated plugin identifier
    /// @param generateRootResourceJar whether to build the Mixin agent root-resource JAR
    /// @return complete immutable version directory
    /// @throws IOException if preparation fails
    private static Path preparePackage(
            Path nplFile,
            Path packageRoot,
            String pluginId,
            boolean generateRootResourceJar
    ) throws IOException {
        String sourceHash = calculateSha256(nplFile);
        Path versionsDirectory = getPluginVersionsDirectory(packageRoot, pluginId);
        Path versionDirectory = versionsDirectory.resolve(sourceHash);
        if (isComplete(versionDirectory, sourceHash, generateRootResourceJar)) {
            return versionDirectory;
        }

        Files.createDirectories(versionsDirectory);
        Path normalizedVersion = versionDirectory.toAbsolutePath().normalize();
        Object localLock = LOCAL_LOCKS.computeIfAbsent(normalizedVersion, ignored -> new Object());
        synchronized (localLock) {
            if (isComplete(versionDirectory, sourceHash, generateRootResourceJar)) {
                return versionDirectory;
            }

            Path lockFile = versionsDirectory.resolve("." + sourceHash + ".lock");
            try (FileChannel channel = FileChannel.open(
                    lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            ); FileLock ignored = channel.lock()) {
                if (isComplete(versionDirectory, sourceHash, generateRootResourceJar)) {
                    return versionDirectory;
                }
                if (Files.exists(versionDirectory)) {
                    throw new IOException("Incomplete immutable plugin package version: " + versionDirectory);
                }

                Path temporaryDirectory = versionsDirectory.resolve(
                        ".tmp-" + sourceHash + "-" + UUID.randomUUID()
                );
                Files.createDirectory(temporaryDirectory);
                try {
                    extractPackage(nplFile, temporaryDirectory);
                    String extractedSourceHash = calculateSha256(nplFile);
                    if (!sourceHash.equals(extractedSourceHash)) {
                        throw new IOException("Plugin package changed while it was being prepared: " + nplFile);
                    }
                    if (generateRootResourceJar) {
                        createRootResourceJar(temporaryDirectory);
                    }
                    Files.writeString(
                            temporaryDirectory.resolve(COMPLETION_MARKER),
                            completionMarker(sourceHash),
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW
                    );
                    publish(temporaryDirectory, versionDirectory);
                    if (!isComplete(versionDirectory, sourceHash, generateRootResourceJar)) {
                        throw new IOException("Published plugin package version is incomplete: " + versionDirectory);
                    }
                    return versionDirectory;
                } finally {
                    deleteRecursively(temporaryDirectory);
                }
            }
        }
    }

    /// Extracts a bounded plugin archive into a newly created directory.
    ///
    /// @param nplFile source package
    /// @param targetDirectory empty target directory
    /// @throws IOException if the archive is unsafe, malformed, or exceeds resource limits
    private static void extractPackage(Path nplFile, Path targetDirectory) throws IOException {
        int entryCount = 0;
        long totalBytes = 0;
        byte[] buffer = new byte[8192];

        try (ZipInputStream zipInput = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(nplFile)),
                StandardCharsets.UTF_8
        )) {
            @Nullable ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("Plugin package contains too many entries");
                }

                Path output = targetDirectory.resolve(entry.getName()).normalize();
                if (!output.startsWith(targetDirectory)) {
                    throw new IOException("Plugin package contains an unsafe path: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }

                @Nullable Path parent = output.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (BufferedOutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(output))) {
                    int read;
                    while ((read = zipInput.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        totalBytes = Math.addExact(totalBytes, read);
                        if (totalBytes > MAX_ARCHIVE_BYTES) {
                            throw new IOException("Plugin package expands beyond the allowed size");
                        }
                        outputStream.write(buffer, 0, read);
                    }
                }
            }
        } catch (ArithmeticException exception) {
            throw new IOException("Plugin package size overflow", exception);
        }

        if (!Files.isRegularFile(targetDirectory.resolve(PLUGIN_MANIFEST))) {
            throw new IOException("Extracted package has no " + PLUGIN_MANIFEST);
        }
    }

    /// Creates the immutable JAR used to expose loose package-root resources to the system loader.
    ///
    /// @param packageDirectory prepared package directory not yet published
    /// @throws IOException if the resource JAR cannot be created
    private static void createRootResourceJar(Path packageDirectory) throws IOException {
        Path output = packageDirectory.resolve(ROOT_RESOURCE_JAR);
        try (JarOutputStream jarOutput = new JarOutputStream(
                new BufferedOutputStream(Files.newOutputStream(output, StandardOpenOption.CREATE_NEW))
        ); Stream<Path> files = Files.walk(packageDirectory)) {
            List<Path> resourceFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.equals(output))
                    .filter(path -> !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .toList();
            for (Path file : resourceFiles) {
                String entryName = packageDirectory.relativize(file).toString().replace('\\', '/');
                JarEntry jarEntry = new JarEntry(entryName);
                jarEntry.setTime(0);
                jarOutput.putNextEntry(jarEntry);
                try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(file))) {
                    input.transferTo(jarOutput);
                }
                jarOutput.closeEntry();
            }
        }
    }

    /// Publishes a prepared directory without replacing an existing immutable version.
    ///
    /// @param temporaryDirectory complete temporary directory
    /// @param versionDirectory final content-addressed directory
    /// @throws IOException if publication fails
    private static void publish(Path temporaryDirectory, Path versionDirectory) throws IOException {
        try {
            Files.move(temporaryDirectory, versionDirectory, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporaryDirectory, versionDirectory);
        }
    }

    /// Returns whether a published directory contains a valid completion marker and required files.
    ///
    /// @param versionDirectory candidate immutable version
    /// @param sourceHash expected source package hash
    /// @param requireRootResourceJar whether the Mixin root-resource JAR is required
    /// @return whether the version can be safely reused
    private static boolean isComplete(
            Path versionDirectory,
            String sourceHash,
            boolean requireRootResourceJar
    ) throws IOException {
        if (!Files.isRegularFile(versionDirectory.resolve(PLUGIN_MANIFEST))) {
            return false;
        }
        if (requireRootResourceJar && !Files.isRegularFile(versionDirectory.resolve(ROOT_RESOURCE_JAR))) {
            return false;
        }
        Path marker = versionDirectory.resolve(COMPLETION_MARKER);
        return Files.isRegularFile(marker)
                && completionMarker(sourceHash).equals(Files.readString(marker, StandardCharsets.UTF_8));
    }

    /// Builds the exact completion marker contents for one source hash.
    ///
    /// @param sourceHash source package SHA-256
    /// @return marker text
    private static String completionMarker(String sourceHash) {
        return LAYOUT_VERSION + "\n" + sourceHash + "\n";
    }

    /// Deletes a temporary directory tree owned by the current preparation attempt.
    ///
    /// @param path temporary path
    /// @throws IOException if cleanup fails
    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    /// Prevents construction of the immutable package utility.
    private PluginPackageVersions() {
    }
}

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

import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.OperatingSystem;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Manages the embedded Node.js runtime used to execute JavaScript plugins.
 * <p>
 * HMCL always uses its own managed Node.js installation under {@code .hmcl/nodejs/}
 * and never reads any system-installed JavaScript runtime, avoiding version
 * compatibility issues with user-installed Node.js.
 */
public final class NodeJSManager {

    /** Pinned Node.js version. All JS plugins run on exactly this version. */
    public static final String NODE_VERSION = "v24.18.0";

    private static final String NODE_BASE_URL = "https://nodejs.org/dist/" + NODE_VERSION + "/";

    private static final Path NODE_DIR = Metadata.HMCL_LOCAL_HOME.resolve("nodejs");
    /** After installation the runtime always lives at {@code .hmcl/nodejs/current}. */
    private static final Path NODE_HOME = NODE_DIR.resolve("current");

    private NodeJSManager() {
    }

    /**
     * Get the managed Node.js executable.
     *
     * @return path to the node executable, or {@code null} if not installed
     */
    public static Path getNodeExecutable() {
        Path nodeExe = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                ? NODE_HOME.resolve("node.exe")
                : NODE_HOME.resolve("bin").resolve("node");
        if (Files.isRegularFile(nodeExe)) {
            return nodeExe;
        }
        return null;
    }

    /**
     * Check whether the managed Node.js runtime is installed.
     */
    public static boolean isNodeInstalled() {
        return getNodeExecutable() != null;
    }

    /**
     * Build the download URL of the binary archive for the current platform.
     * Only binary archives (zip / tar.gz) are used - never source tarballs or installers.
     *
     * @return the download URL, or {@code null} if the platform is unsupported
     */
    public static String getDownloadUrl() {
        String platform;
        String extension;

        switch (OperatingSystem.CURRENT_OS) {
            case WINDOWS:
                platform = "win";
                extension = "zip";
                break;
            case MACOS:
                platform = "darwin";
                extension = "tar.gz";
                break;
            case LINUX:
                platform = "linux";
                extension = "tar.gz";
                break;
            default:
                return null;
        }

        String archStr;
        if (Architecture.SYSTEM_ARCH == Architecture.X86_64) {
            archStr = "x64";
        } else if (Architecture.SYSTEM_ARCH == Architecture.ARM64) {
            archStr = "arm64";
        } else if (Architecture.SYSTEM_ARCH == Architecture.ARM32 && OperatingSystem.CURRENT_OS == OperatingSystem.LINUX) {
            archStr = "armv7l";
        } else {
            return null;
        }

        // e.g. node-v24.18.0-win-x64.zip / node-v24.18.0-linux-x64.tar.gz
        return NODE_BASE_URL + "node-" + NODE_VERSION + "-" + platform + "-" + archStr + "." + extension;
    }

    /**
     * Human readable platform description, e.g. "windows x86_64".
     */
    public static String getPlatformDescription() {
        return OperatingSystem.CURRENT_OS.getCheckedName() + " " + Architecture.SYSTEM_ARCH.getCheckedName();
    }

    /**
     * Download the Node.js binary archive for this platform and install it
     * into {@code .hmcl/nodejs/current}. Blocking; call from a background thread.
     */
    public static void downloadAndInstall() throws IOException {
        String downloadUrl = getDownloadUrl();
        if (downloadUrl == null) {
            throw new IOException("Unsupported platform: " + getPlatformDescription());
        }

        LOG.info("Downloading Node.js " + NODE_VERSION + " for " + getPlatformDescription());
        LOG.info("Download URL: " + downloadUrl);

        // Clean previous installation
        if (Files.exists(NODE_DIR)) {
            FileUtils.deleteDirectory(NODE_DIR);
        }
        Files.createDirectories(NODE_DIR);

        boolean isZip = downloadUrl.endsWith(".zip");
        Path archive = NODE_DIR.resolve("node-download" + (isZip ? ".zip" : ".tar.gz"));

        try (InputStream in = new URL(downloadUrl).openStream()) {
            Files.copy(in, archive, StandardCopyOption.REPLACE_EXISTING);
        }

        LOG.info("Downloaded Node.js archive (" + Files.size(archive) + " bytes), extracting...");

        if (isZip) {
            extractZip(archive, NODE_DIR);
        } else {
            extractTarGz(archive, NODE_DIR);
        }

        Files.deleteIfExists(archive);

        // The archive contains a single top-level directory like "node-v24.18.0-win-x64";
        // rename it to the stable "current" directory.
        Path extracted = findExtractedNodeDir();
        if (extracted == null) {
            throw new IOException("Extracted Node.js directory not found in " + NODE_DIR);
        }
        Files.move(extracted, NODE_HOME, StandardCopyOption.REPLACE_EXISTING);

        // Ensure the node binary is executable on unix systems
        if (OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS) {
            Path nodeExe = NODE_HOME.resolve("bin").resolve("node");
            if (Files.exists(nodeExe) && !nodeExe.toFile().setExecutable(true)) {
                LOG.warning("Failed to mark node binary as executable: " + nodeExe);
            }
        }

        if (!isNodeInstalled()) {
            throw new IOException("Node.js extraction completed but executable not found");
        }

        LOG.info("Node.js " + NODE_VERSION + " installed at: " + NODE_HOME);
    }

    private static Path findExtractedNodeDir() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(NODE_DIR, "node-" + NODE_VERSION + "-*")) {
            for (Path path : stream) {
                if (Files.isDirectory(path)) {
                    return path;
                }
            }
        }
        return null;
    }

    /**
     * Extract ZIP archive (Windows).
     */
    private static void extractZip(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path targetPath = targetDir.resolve(entry.getName()).normalize();
                // Protect against zip-slip
                if (!targetPath.startsWith(targetDir)) {
                    throw new IOException("Illegal zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(zis, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * Extract tar.gz archive (Linux/macOS).
     */
    private static void extractTarGz(Path tarGzFile, Path targetDir) throws IOException {
        // tar is universally available on macOS / Linux
        ProcessBuilder pb = new ProcessBuilder(
                "tar", "-xzf", tarGzFile.toString(), "-C", targetDir.toString());
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            // Drain output to avoid blocking
            try (InputStream in = process.getInputStream()) {
                in.readAllBytes();
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("tar extraction failed with exit code " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Extraction interrupted", e);
        }
    }

    /**
     * Remove the managed Node.js runtime.
     */
    public static void uninstall() throws IOException {
        if (Files.exists(NODE_DIR)) {
            FileUtils.deleteDirectory(NODE_DIR);
            LOG.info("Managed Node.js runtime removed");
        }
    }
}

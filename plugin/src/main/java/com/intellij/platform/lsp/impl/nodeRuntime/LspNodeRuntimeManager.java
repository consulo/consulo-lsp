// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

// This file works on the local machine only, so it uses `java.nio` directly instead of the Eel file
// API. See the class documentation of `LspNodeRuntimeManager` for the reason.
// `File.pathSeparator` is the separator of the `PATH` variable, not a file path, and it has no
// `java.nio` equal. The platform's own `PathEnvironmentVariableUtil` suppresses `IO_FILE_USAGE` for
// the same reason.

package com.intellij.platform.lsp.impl.nodeRuntime;

import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.Application;
import consulo.application.progress.ProgressIndicator;
import consulo.component.ProcessCanceledException;
import consulo.container.boot.ContainerPathManager;
import consulo.http.HttpRequests;
import consulo.logging.Logger;
import consulo.lsp.impl.compat.Decompressor;
import consulo.platform.Platform;
import consulo.process.cmd.GeneralCommandLine;
import consulo.process.util.CapturingProcessUtil;
import consulo.process.util.ProcessOutput;
import consulo.util.io.DigestUtil;
import consulo.util.io.FileUtil;
import consulo.util.lang.VersionComparatorUtil;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Downloads and manages one pinned Node.js runtime for LSP servers that ship as an npm package.
 * <p>
 * The runtime is a copy of the Node.js distribution from {@code nodejs.org}, extracted into the IDE system
 * directory ({@code ContainerPathManager.getSystemDir()}, see {@link #runtimesDir}). That directory holds data the IDE can
 * build again, so a user who deletes it pays one more download and nothing else. A server descriptor
 * gets {@code node}, {@code npm} and {@code npx} from the runtime, so the user does not have to install Node.js.
 * <p>
 * This code is a reduced copy of the ACP agent runtime manager. Three things are cut on purpose:
 * <ul>
 * <li><b>The local machine only.</b> The original keys every path by an EEL descriptor, because an agent
 *   can run on WSL or in Docker. This copy resolves the host with {@link LspNodeHost#current()}. A project on
 *   WSL gets the Windows runtime.</li>
 * <li><b>No {@code npm install} stage.</b> The original installs the agent package into its own folder and runs
 *   it with {@code npx --no}. This copy provides the runtime only, so a caller still uses {@code npx --yes}, which
 *   downloads the server package on the first launch.</li>
 * <li><b>The managed runtime only.</b> A {@code node} on the local {@code PATH} is never found and never used. A
 *   process gets the runtime and the operating system shell on its {@code PATH}, and nothing else. See
 *   {@link #managedPathValue}.</li>
 * </ul>
 *
 * @see LspNodeRuntime
 */
@ServiceAPI(ComponentScope.APPLICATION)
@ServiceImpl
@Singleton
public class LspNodeRuntimeManager {
    private static final Logger LOG = Logger.getInstance(LspNodeRuntimeManager.class);

    private static final String NODE_DOWNLOAD_BASE = "https://nodejs.org/dist";

    /**
     * Suffix of the sibling staging directory an install is built in, before the atomic rename onto
     * the final version directory. See {@link #stagingDirFor} and {@link #promoteStagedInstall}.
     */
    private static final String INCOMPLETE_INSTALL_DIR_SUFFIX = ".incomplete";

    /**
     * Generous cap for the post-install {@code --version} smoke test. On Windows the first run can be slow,
     * because antivirus scans the freshly extracted {@code node_modules}. A false timeout discards a good
     * install and starts a new download, so this errs high.
     */
    private static final int INSTALL_VALIDATION_TIMEOUT_MS = 30_000;

    /**
     * The pinned Node.js version. 24.19.0 is the LTS line (Krypton).
     * <p>
     * Change this together with {@link #PLATFORM_TO_NODE_ARTIFACT}.
     */
    public static final String NODE_VERSION = "24.19.0";

    /**
     * The Node.js distribution archives for {@link #NODE_VERSION}: the {@code <os>-<arch>} file name part, plus
     * the SHA-256 the download is verified against. This map also states which hosts Node.js is
     * available on.
     * <p>
     * You MUST update this together with {@link #NODE_VERSION}. Replace every hash with the one from
     * {@code https://nodejs.org/dist/v<version>/SHASUMS256.txt}.
     */
    private static final Map<LspNodeHost, NodeArtifact> PLATFORM_TO_NODE_ARTIFACT = Map.of(
        LspNodeHost.MACOS_ARM64, new NodeArtifact("darwin-arm64", "8294b7aa9b03997481c06babf1e8b270c859358f27da57a11509afe537ac381d"),
        LspNodeHost.MACOS_X86_64, new NodeArtifact("darwin-x64", "d1b5e999db158c62fe8f7267a4476b035d8bd93b1a605bac24a3f0dd166e3316"),
        LspNodeHost.LINUX_ARM64, new NodeArtifact("linux-arm64", "d28c8a5bf0a808f0ed434a1dce8c54ae98f0371c0bd86ac58abc613f73e6643f"),
        LspNodeHost.LINUX_X86_64, new NodeArtifact("linux-x64", "f625d97cd707df4ff96254916fbc5ff014f09c09effe5a1e0ca8f6d41a8789d4"),
        LspNodeHost.WINDOWS_X86_64, new NodeArtifact("win-x64", "57f71ab3652e797d84acddc79c81cc9ff1c6ddb2a1974cdb83f00fee9bff4c73"),
        LspNodeHost.WINDOWS_ARM64, new NodeArtifact("win-arm64", "8502f4a50b458d4cc38ed8f2001556c2cd239d464920f74017926ccb1e1c157f")
    );

    /**
     * Serializes the download, so one open file per editor tab cannot start several downloads of the
     * same archive.
     */
    private final ReentrantLock myDownloadLock = new ReentrantLock();

    /**
     * The runtime this session found or installed. The pin does not move while the IDE runs, so a hit
     * here keeps {@link #getRuntime} free of I/O for every later call.
     * <p>
     * A tree that something removes from outside therefore stays cached. The server then fails to start,
     * instead of a new download. The ACP manager accepts the same result for its package directory.
     */
    private volatile @Nullable LspNodeRuntime myCachedRuntime = null;

    public static LspNodeRuntimeManager getInstance() {
        return Application.get().getInstance(LspNodeRuntimeManager.class);
    }

    /**
     * The {@code PATH} a process gets: {@code binDir} first, then the directories that hold the operating system
     * shell.
     * <p>
     * The local {@code PATH} stays out, so only the managed runtime counts and a {@code node} the user installed can
     * never win. {@code binDir} comes first for the same reason.
     * <p>
     * The shell directories must stay in. {@code npm exec} runs the resolved binary through a shell, so npm
     * fails with {@code spawn sh ENOENT} when it cannot find {@code sh}. These directories hold the shell itself and
     * the other standard commands, and they hold no version manager, so they add no second {@code node}.
     */
    public static String managedPathValue(Path binDir) {
        List<String> entries = new ArrayList<>();
        entries.add(binDir.toString());
        entries.addAll(shellDirectories());
        return String.join(File.pathSeparator, entries);
    }

    /**
     * The directories that hold the operating system shell.
     */
    public static List<String> shellDirectories() {
        LspNodeHost host = LspNodeHost.current();
        if (host != null && host.isWindows()) {
            String systemRoot = Platform.current().os().getEnvironmentVariable("SystemRoot");
            if (systemRoot == null) {
                systemRoot = "C:\\Windows";
            }
            return List.of(systemRoot + "\\System32", systemRoot);
        }
        else {
            return List.of("/usr/bin", "/bin");
        }
    }

    /**
     * The directory that holds one version directory per downloaded runtime.
     */
    public static Path runtimesDir() {
        return ContainerPathManager.get().getSystemDir().resolve("lsp-client").resolve("node");
    }

    /**
     * The archive name for {@code host}, or {@code null} when Node.js has no distribution for it.
     */
    public static @Nullable String archiveFileName(LspNodeHost host) {
        String prefix = distributionPrefix(host);
        if (prefix == null) {
            return null;
        }
        return prefix + "." + (host.isWindows() ? "zip" : "tar.gz");
    }

    /**
     * The single directory the archive wraps everything in. Extraction strips it.
     */
    private static @Nullable String distributionPrefix(LspNodeHost host) {
        NodeArtifact artifact = PLATFORM_TO_NODE_ARTIFACT.get(host);
        if (artifact == null) {
            return null;
        }
        return "node-v" + NODE_VERSION + "-" + artifact.getPlatformSuffix();
    }

    /**
     * Finds an executable in the Node.js distribution layout under {@code versionDir}:
     * {@code <name>.cmd} or {@code <name>.exe} at the root on Windows, next to {@code node_modules/}, and {@code bin/<name>}
     * on Unix, where npm reaches {@code node_modules/} through its own {@code ../lib/node_modules} lookup.
     */
    public static @Nullable Path findNodeExecutable(Path versionDir, String name, LspNodeHost host) {
        Path execHome = host.isWindows() ? versionDir : versionDir.resolve("bin");
        if (!Files.exists(execHome)) {
            return null;
        }

        for (String ext : host.isWindows() ? List.of(".exe", ".cmd", ".bat") : List.of("")) {
            Path exec = execHome.resolve(name + ext);
            if (Files.exists(exec) && Files.isRegularFile(exec)) {
                return exec;
            }
        }
        return null;
    }

    /**
     * The sibling staging directory an install is built in, before the atomic rename onto
     * {@code installDir}. It sits next to {@code installDir}, so the rename stays on one filesystem. Its name is
     * predictable, so that even a leftover from a hard crash is reclaimed by the next
     * {@link #cleanRuntimeDir}.
     */
    public static Path stagingDirFor(Path installDir) {
        return installDir.resolveSibling(installDir.getFileName().toString() + INCOMPLETE_INSTALL_DIR_SUFFIX);
    }

    /**
     * Prepares the staging {@code dir} for a new install. It deletes whatever an interrupted attempt left
     * there, which is about 100 MB for Node.js. The package cache next to it goes with it, and npm
     * builds the cache again on the first use.
     * <p>
     * Extraction over leftovers is not an option. An overwrite of a file that antivirus or the Search
     * Indexer holds open fails the whole install.
     *
     * @return false if the cleanup failed, for example because a file is still locked. The caller must
     * stop the install and try again later, instead of extraction into a dirty directory.
     */
    public static boolean cleanRuntimeDir(Path dir) {
        try {
            if (Files.exists(dir)) {
                List<String> leftovers;
                try (Stream<Path> stream = Files.list(dir)) {
                    leftovers = stream.map(it -> it.getFileName().toString()).collect(Collectors.toList());
                }
                LOG.info("Cleaning the staging dir before an install: " + dir + ", leftovers: " + leftovers);
            }
            deleteRecursively(dir);
            Files.createDirectories(dir);
            return true;
        }
        catch (Exception e) {
            LOG.warn("Failed to clean the staging dir before an install: " + dir, e);
            return false;
        }
    }

    /**
     * Best-effort removal of a staging directory, called from the install's {@code finally}. It does nothing
     * after {@link #promoteStagedInstall} renamed the directory away, because the path is gone. In every other
     * case it removes the partial tree a failed install left. It never throws, because a cleanup failure
     * must not hide the original result.
     */
    private static void deleteStagingQuietly(Path stagingDir) {
        try {
            deleteRecursively(stagingDir);
        }
        catch (Exception e) {
            LOG.error("Failed to clean up the staging dir " + stagingDir, e);
        }
    }

    /**
     * Publishes a complete install. It renames {@code stagingDir} onto {@code installDir} in one step, so
     * {@link #findRuntimeInVersionDir} never sees a partial tree and no completion marker is necessary.
     * <p>
     * It removes an existing {@code installDir} first, because {@link StandardCopyOption#ATOMIC_MOVE} refuses to
     * replace a directory. Such a directory is a leftover, never a usable runtime, because
     * {@link #getRuntime} returns before the download in that case.
     *
     * @return false if the rename failed. The staged directory stays for the next install to clean.
     */
    public static boolean promoteStagedInstall(Path stagingDir, Path installDir) {
        try {
            if (Files.exists(installDir)) {
                LOG.info("Removing the existing runtime dir before the staged install goes in: " + installDir);
                deleteRecursively(installDir);
            }
            try {
                Files.move(stagingDir, installDir, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e) {
                // Every filesystem we install on supports an atomic move, so this must not happen. It is
                // logged as an error to learn about the environment. The staged dir is complete, so a plain
                // rename on the same filesystem still publishes a complete install.
                LOG.error("An atomic move is unexpectedly unsupported for " + stagingDir + " -> " + installDir + ". Falling back to a plain move.", e);
                Files.move(stagingDir, installDir);
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("Promoted the staged install " + stagingDir + " -> " + installDir);
            }
            return true;
        }
        catch (Exception e) {
            LOG.warn("Failed to promote the staged install " + stagingDir + " -> " + installDir, e);
            return false;
        }
    }

    /**
     * Removes every version directory that is not {@link #NODE_VERSION}. It runs once, after a successful
     * install of that pin.
     * <p>
     * {@link #getRuntime} only looks at the pinned version, so every other tree is dead weight, which is
     * about 100 MB per Node.js release.
     * <p>
     * The ACP manager removes only <i>older</i> versions, because it shares its directory with a second
     * copy of that engine that pins its own version. This directory has one owner, so it removes every
     * other version.
     * <p>
     * Best-effort: a locked file must not fail the install that just succeeded.
     */
    public static void deleteOtherRuntimeVersions(Path runtimesDir) {
        try {
            List<Path> stale;
            try (Stream<Path> stream = Files.list(runtimesDir)) {
                stale = stream.filter(LspNodeRuntimeManager::isStaleRuntimeVersionDir).collect(Collectors.toList());
            }
            for (Path dir : stale) {
                LOG.info("Removing a superseded Node.js runtime: " + dir);
                deleteRecursively(dir);
            }
        }
        catch (Exception e) {
            LOG.warn("Failed to remove the superseded Node.js runtimes under " + runtimesDir, e);
        }
    }

    /**
     * Whether {@code candidate} is a version directory that {@link #NODE_VERSION} replaces.
     * <p>
     * A staging directory belongs to an install in flight, so it stays. {@link VersionComparatorUtil} keeps
     * a directory with a name this code cannot read, instead of a removal of something unknown.
     */
    private static boolean isStaleRuntimeVersionDir(Path candidate) {
        String name = candidate.getFileName().toString();
        return !name.endsWith(INCOMPLETE_INSTALL_DIR_SUFFIX) && VersionComparatorUtil.compare(name, NODE_VERSION) != 0;
    }

    /**
     * Makes {@code path} executable on Unix.
     */
    private static void makeExecutable(Path path, LspNodeHost host) {
        if (host.isWindows()) {
            return;
        }

        try {
            Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(path));
            permissions.addAll(Set.of(
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_EXECUTE
            ));
            Files.setPosixFilePermissions(path, permissions);
        }
        catch (Exception e) {
            LOG.warn("Failed to set the executable permission on " + path, e);
        }
    }

    /**
     * Verifies the SHA-256 of {@code archivePath} against {@code expectedChecksum}, which is lowercase hex, as
     * {@link #sha256Hex} returns. It returns false on a mismatch, so the caller can fail the install.
     */
    private static boolean verifyChecksum(Path archivePath, String fileName, String expectedChecksum) throws IOException {
        String actualChecksum = sha256Hex(archivePath);
        if (!actualChecksum.equals(expectedChecksum)) {
            LOG.warn("Checksum mismatch for " + fileName + ": expected " + expectedChecksum + ", got " + actualChecksum);
            return false;
        }
        return true;
    }

    /**
     * The lowercase hex SHA-256 of a file.
     */
    private static String sha256Hex(Path file) throws IOException {
        MessageDigest digest = DigestUtil.sha256();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Deletes the path recursively; throws on a failure.
     */
    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path) && !Files.isSymbolicLink(path)) {
            return;
        }
        if (!FileUtil.delete(path)) {
            throw new IOException("Failed to delete " + path);
        }
    }

    /**
     * Runs {@code <executable> --version} to prove that the install is complete, and that it runs through the
     * same entry point a server uses. This catches what a file existence check cannot: a partial
     * extraction, a binary for the wrong architecture, an executable that antivirus blocks.
     *
     * @return the version the executable reports, or null if it failed to run
     */
    private static @Nullable String runVersionCheck(Path executable, Path runtimeDir) {
        GeneralCommandLine commandLine = new GeneralCommandLine(executable.toString(), "--version");
        for (Map.Entry<String, String> entry : nodeEnvironmentVariables(runtimeDir).entrySet()) {
            commandLine.withEnvironment(entry.getKey(), entry.getValue());
        }
        commandLine.withEnvironment("PATH", managedPathValue(Objects.requireNonNull(executable.getParent())));

        String commandDescription = commandLine.getCommandLineString();
        try {
            long startedAt = System.currentTimeMillis();
            ProcessOutput output = CapturingProcessUtil.execAndGetOutput(commandLine, INSTALL_VALIDATION_TIMEOUT_MS);
            if (output.isTimeout()) {
                LOG.warn("The runtime check timed out after " + INSTALL_VALIDATION_TIMEOUT_MS + "ms: " + commandDescription);
                return null;
            }
            else if (output.getExitCode() != 0) {
                LOG.warn("The runtime check failed with exit code " + output.getExitCode() + ": " + commandDescription + "\n" + output.getStderr().trim());
                return null;
            }
            else {
                String reportedVersion = output.getStdout().trim();
                LOG.info("The runtime check passed in " + (System.currentTimeMillis() - startedAt) + "ms: npx --version -> " + reportedVersion);
                return reportedVersion;
            }
        }
        catch (CancellationException | ProcessCanceledException e) {
            // A cancelled check is not a failed check. Without this the install would report a broken
            // runtime and start a new download.
            throw e;
        }
        catch (Exception e) {
            LOG.warn("The runtime check failed to run: " + commandDescription, e);
            return null;
        }
    }

    /**
     * The environment a process needs to use {@code runtimeDir} as its Node.js installation.
     * <p>
     * {@code ~/.npmrc} is left alone on purpose. A corporate registry mirror, its token and its certificate
     * live there. npm ranks an environment variable above every {@code .npmrc} file, so each key here is a
     * pin the user configuration cannot move.
     */
    public static Map<String, String> nodeEnvironmentVariables(Path runtimeDir) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("NPM_CONFIG_CACHE", runtimeDir.resolve("npm-cache").toString());
        result.put("NPM_CONFIG_MIN_RELEASE_AGE", "0");
        // A server package installs as published. A stricter user policy only fails it.
        result.put("NPM_CONFIG_ENGINE_STRICT", "false");
        result.put("NPM_CONFIG_STRICT_PEER_DEPS", "false");
        // Trust the OS certificate store too, so npm works behind a corporate TLS proxy.
        result.put("NODE_USE_SYSTEM_CA", "1");
        return result;
    }

    /**
     * The runtime, if it is already on disk. This function never downloads, so it is safe to call from
     * {@link com.intellij.platform.lsp.api.LspIntegrationProvider#fileOpened}, which holds the read lock.
     *
     * @return null when the runtime needs a download, or when Node.js has no distribution for this host
     */
    public @Nullable LspNodeRuntime getRuntime() {
        LspNodeRuntime cached = myCachedRuntime;
        if (cached != null) {
            return cached;
        }

        LspNodeHost host = LspNodeHost.current();
        if (host == null) {
            return null;
        }
        LspNodeRuntime runtime = findRuntimeInVersionDir(runtimesDir().resolve(NODE_VERSION), host);
        if (runtime != null) {
            myCachedRuntime = runtime;
        }
        return runtime;
    }

    /**
     * The runtime, downloaded if it is not on disk yet. Must be called on a background thread.
     *
     * @return null if the download failed, or if Node.js has no distribution for this host
     */
    public @Nullable LspNodeRuntime ensureRuntime() {
        return ensureRuntime(null);
    }

    /**
     * The runtime, downloaded if it is not on disk yet. Must be called on a background thread.
     *
     * @param indicator reports the download progress, and cancels it
     * @return null if the download failed, or if Node.js has no distribution for this host
     */
    public @Nullable LspNodeRuntime ensureRuntime(@Nullable ProgressIndicator indicator) {
        LspNodeRuntime existing = getRuntime();
        if (existing != null) {
            return existing;
        }

        myDownloadLock.lock();
        try {
            // Another thread may have finished the download while this one waited for the lock.
            LspNodeRuntime runtime = getRuntime();
            if (runtime != null) {
                return runtime;
            }

            LspNodeHost host = LspNodeHost.current();
            if (host == null) {
                LOG.warn("Node.js has no distribution for this host: " + LspNodeHost.currentDescription());
                return null;
            }
            Path runtimesDir = runtimesDir();
            Path installDir = runtimesDir.resolve(NODE_VERSION);
            if (!installRuntime(host, installDir, indicator)) {
                return null;
            }

            // The pin just moved, which is the one moment the trees it replaces are known to be unused. This
            // runs inside the lock, so no other install can look at them.
            deleteOtherRuntimeVersions(runtimesDir);

            LspNodeRuntime installed = findRuntimeInVersionDir(installDir, host);
            if (installed != null) {
                myCachedRuntime = installed;
            }
            return installed;
        }
        finally {
            myDownloadLock.unlock();
        }
    }

    /**
     * Finds a runtime in one version directory.
     * <p>
     * The version directory only ever appears complete. An install is built in a sibling
     * {@code <version>.incomplete} directory, and renamed into place as the last step. See
     * {@link #promoteStagedInstall}. The directory together with its executable is therefore the whole
     * completeness signal.
     */
    private @Nullable LspNodeRuntime findRuntimeInVersionDir(Path versionDir, LspNodeHost host) {
        if (!Files.exists(versionDir)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Node.js runtime lookup: the version dir does not exist: " + versionDir);
            }
            return null;
        }

        Path node = findNodeExecutable(versionDir, "node", host);
        Path npx = findNodeExecutable(versionDir, "npx", host);
        if (node == null || npx == null || !Files.isExecutable(node)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Node.js runtime lookup: an executable is missing in " + versionDir + " (node=" + node + ", npx=" + npx + ")");
            }
            return null;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Node.js runtime lookup: found a valid install, node=" + node);
        }
        return new LspNodeRuntime(NODE_VERSION, versionDir, node, npx, findNodeExecutable(versionDir, "npm", host));
    }

    /**
     * Downloads the archive for {@code host}, verifies it, extracts it, checks it, and publishes it at
     * {@code installDir}.
     *
     * @return true if {@code installDir} now holds a complete runtime
     */
    private boolean installRuntime(LspNodeHost host, Path installDir, @Nullable ProgressIndicator indicator) {
        NodeArtifact artifact = PLATFORM_TO_NODE_ARTIFACT.get(host);
        String fileName = archiveFileName(host);
        String distPrefix = distributionPrefix(host);
        if (artifact == null || fileName == null || distPrefix == null) {
            throw new IllegalStateException("No Node.js artifact for " + host);
        }
        String url = NODE_DOWNLOAD_BASE + "/v" + NODE_VERSION + "/" + fileName;

        // The install is built in a sibling staging dir, and renamed onto installDir only after it is
        // complete and checked, so a concurrent lookup never sees a torn tree.
        Path stagingDir = stagingDirFor(installDir);
        try {
            LOG.info("Installing Node.js v" + NODE_VERSION + " for " + host + " from " + url + " into " + installDir);
            if (indicator != null) {
                indicator.setIndeterminate(false);
                indicator.setText(LspLocalize.lspRuntimeNodeProgressDownloading(NODE_VERSION));
            }

            if (!cleanRuntimeDir(stagingDir)) {
                return false;
            }

            Path archivePath = stagingDir.resolve(fileName);
            HttpRequests.request(url)
                .userAgent("JetBrains-LSP-Node-Runtime/1.0")
                .saveToFile(archivePath, indicator);

            if (indicator != null) {
                indicator.checkCanceled();
                indicator.setIndeterminate(true);
                indicator.setText(LspLocalize.lspRuntimeNodeProgressVerifying());
            }
            if (!verifyChecksum(archivePath, fileName, artifact.getSha256())) {
                return false;
            }

            if (indicator != null) {
                indicator.checkCanceled();
                indicator.setText(LspLocalize.lspRuntimeNodeProgressExtracting());
            }
            // Strip the `node-v<ver>-<os>-<arch>/` wrapper per entry during extraction, so the distribution
            // lands directly in the staging dir. This needs no move after the extraction, which is fragile
            // on Windows while antivirus holds a handle, and no search for the wrapper at lookup time. A
            // relative symlink inside the archive, for example `bin/npm` -> `../lib/node_modules/...`,
            // moves with it and stays valid.
            if (host.isWindows()) {
                new Decompressor.Zip(archivePath).removePrefixPath(distPrefix).extract(stagingDir);
            }
            else {
                new Decompressor.Tar(archivePath).removePrefixPath(distPrefix).extract(stagingDir);
            }

            Files.deleteIfExists(archivePath);
            Files.createDirectories(stagingDir.resolve("npm-cache"));

            Path nodeBin = findNodeExecutable(stagingDir, "node", host);
            Path npxBin = findNodeExecutable(stagingDir, "npx", host);
            if (nodeBin == null || npxBin == null) {
                LOG.warn("No Node.js executable after the extraction, prefix '" + distPrefix + "' stripped: node=" + nodeBin + ", npx=" + npxBin + " in " + stagingDir);
                return false;
            }
            makeExecutable(nodeBin, host);
            makeExecutable(npxBin, host);
            Path npmBin = findNodeExecutable(stagingDir, "npm", host);
            if (npmBin != null) {
                makeExecutable(npmBin, host);
            }

            if (runVersionCheck(npxBin, stagingDir) == null) {
                return false;
            }
            if (!promoteStagedInstall(stagingDir, installDir)) {
                return false;
            }

            LOG.info("Installed Node.js v" + NODE_VERSION + " to " + installDir);
            return true;
        }
        catch (CancellationException | ProcessCanceledException e) {
            // The user pressed Stop on the progress bar, or a caller's future was cancelled.
            // `ProcessCanceledException` is not a `CancellationException`, so both are caught;
            // cancellation must never become a logged failure. The `finally` below removes the partial tree.
            throw e;
        }
        catch (Exception e) {
            LOG.warn("Failed to download Node.js v" + NODE_VERSION, e);
            return false;
        }
        finally {
            // The rename takes the staging dir away on success. On a failure this removes the partial tree,
            // instead of a wait for the next install to clean it.
            deleteStagingQuietly(stagingDir);
        }
    }

    private static final class NodeArtifact {
        private final String myPlatformSuffix;
        private final String mySha256;

        private NodeArtifact(String platformSuffix, String sha256) {
            myPlatformSuffix = platformSuffix;
            mySha256 = sha256;
        }

        String getPlatformSuffix() {
            return myPlatformSuffix;
        }

        String getSha256() {
            return mySha256;
        }
    }
}

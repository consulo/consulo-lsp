// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.nodeRuntime;

import consulo.process.cmd.GeneralCommandLine;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.nio.file.Path;
import java.util.Map;

/**
 * One installed Node.js runtime.
 * <p>
 * {@code version}: the Node.js version, which is always {@link LspNodeRuntimeManager#NODE_VERSION};
 * {@code runtimeDir}: the root of the installation, next to its {@code npm-cache/};
 * {@code node}: the {@code node} executable;
 * {@code npx}: the {@code npx} executable, which a server descriptor launches;
 * {@code npm}: the {@code npm} executable, absent in a distribution that ships without it.
 */
public class LspNodeRuntime {
    private final String myVersion;
    private final Path myRuntimeDir;
    private final Path myNode;
    private final Path myNpx;
    private final @Nullable Path myNpm;

    public LspNodeRuntime(String version, Path runtimeDir, Path node, Path npx, @Nullable Path npm) {
        myVersion = version;
        myRuntimeDir = runtimeDir;
        myNode = node;
        myNpx = npx;
        myNpm = npm;
    }

    public String getVersion() {
        return myVersion;
    }

    public Path getRuntimeDir() {
        return myRuntimeDir;
    }

    public Path getNode() {
        return myNode;
    }

    public Path getNpx() {
        return myNpx;
    }

    public @Nullable Path getNpm() {
        return myNpm;
    }

    /**
     * The directory that holds {@link #getNode() node}, {@link #getNpx() npx} and {@link #getNpm() npm}.
     */
    public Path getBinDir() {
        return Objects.requireNonNull(myNode.getParent());
    }

    /**
     * @see LspNodeRuntimeManager#nodeEnvironmentVariables
     */
    public Map<String, String> environmentVariables() {
        return LspNodeRuntimeManager.nodeEnvironmentVariables(myRuntimeDir);
    }

    /**
     * Applies this runtime to {@code commandLine}: the npm configuration, and {@link LspNodeRuntimeManager#managedPathValue} as {@code PATH}.
     */
    public GeneralCommandLine applyTo(GeneralCommandLine commandLine) {
        for (Map.Entry<String, String> entry : environmentVariables().entrySet()) {
            commandLine.withEnvironment(entry.getKey(), entry.getValue());
        }
        return commandLine.withEnvironment("PATH", LspNodeRuntimeManager.managedPathValue(getBinDir()));
    }
}

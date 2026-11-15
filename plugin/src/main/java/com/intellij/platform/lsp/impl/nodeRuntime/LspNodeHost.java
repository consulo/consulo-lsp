// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.nodeRuntime;

import consulo.platform.CpuArchitecture;
import consulo.platform.Platform;
import consulo.platform.PlatformOperatingSystem;
import org.jspecify.annotations.Nullable;

/**
 * A host Node.js publishes a distribution for.
 * <p>
 * The ACP manager this code comes from resolves the host through an EEL descriptor, so it can install
 * a runtime on WSL or in Docker. This copy reads the local machine, so a project on WSL gets the
 * Windows runtime.
 */
public enum LspNodeHost {
    MACOS_ARM64(false),
    MACOS_X86_64(false),
    LINUX_ARM64(false),
    LINUX_X86_64(false),
    WINDOWS_X86_64(true),
    WINDOWS_ARM64(true);

    private final boolean myIsWindows;

    LspNodeHost(boolean isWindows) {
        myIsWindows = isWindows;
    }

    public boolean isWindows() {
        return myIsWindows;
    }

    /**
     * The local host, or null when Node.js publishes no distribution for it.
     */
    public static @Nullable LspNodeHost current() {
        Platform platform = Platform.current();
        return of(platform.os(), platform.jvm().arch());
    }

    /**
     * The local OS and architecture, for a log message about an unsupported host.
     */
    public static String currentDescription() {
        Platform platform = Platform.current();
        return platform.os().name() + " " + platform.jvm().arch();
    }

    public static @Nullable LspNodeHost of(PlatformOperatingSystem os, CpuArchitecture arch) {
        if (os.isMac()) {
            if (CpuArchitecture.AARCH64.equals(arch)) {
                return MACOS_ARM64;
            }
            if (CpuArchitecture.X86_64.equals(arch)) {
                return MACOS_X86_64;
            }
            return null;
        }
        if (os.isLinux()) {
            if (CpuArchitecture.AARCH64.equals(arch)) {
                return LINUX_ARM64;
            }
            if (CpuArchitecture.X86_64.equals(arch)) {
                return LINUX_X86_64;
            }
            return null;
        }
        if (os.isWindows()) {
            if (CpuArchitecture.AARCH64.equals(arch)) {
                return WINDOWS_ARM64;
            }
            if (CpuArchitecture.X86_64.equals(arch)) {
                return WINDOWS_X86_64;
            }
            return null;
        }
        return null;
    }
}

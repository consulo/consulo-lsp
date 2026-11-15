// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import consulo.virtualFileSystem.VirtualFile;

import java.util.EventListener;

public interface LspClientManagerListener extends EventListener {
    default void clientAdded(LspClient lspClient) {
    }

    default void clientRemoved(LspClient lspClient) {
    }

    default void serverStateChanged(LspClient lspClient) {
    }

    default void fileOpened(LspClient lspClient, VirtualFile file) {
    }

    default void fileEdited(LspClient lspClient, VirtualFile file) {
    }

    default void diagnosticsReceived(LspClient lspClient, VirtualFile file) {
    }

    default void documentLinksReceived(LspClient lspClient, VirtualFile file) {
    }
}

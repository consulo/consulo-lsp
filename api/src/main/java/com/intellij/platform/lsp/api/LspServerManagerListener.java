// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import consulo.virtualFileSystem.VirtualFile;

/**
 * @deprecated Renamed to {@link LspClientManagerListener}
 */
@Deprecated
@SuppressWarnings("deprecation")
public interface LspServerManagerListener extends LspClientManagerListener {
    default void serverStateChanged(LspServer lspServer) {
    }

    default void fileOpened(LspServer lspServer, VirtualFile file) {
    }

    default void fileEdited(LspServer lspServer, VirtualFile file) {
    }

    default void diagnosticsReceived(LspServer lspServer, VirtualFile file) {
    }

    default void documentLinksReceived(LspServer lspServer, VirtualFile file) {
    }

    @Override
    default void serverStateChanged(LspClient lspClient) {
        serverStateChanged((LspServer) lspClient);
    }

    @Override
    default void fileOpened(LspClient lspClient, VirtualFile file) {
        fileOpened((LspServer) lspClient, file);
    }

    @Override
    default void fileEdited(LspClient lspClient, VirtualFile file) {
        fileEdited((LspServer) lspClient, file);
    }

    @Override
    default void diagnosticsReceived(LspClient lspClient, VirtualFile file) {
        diagnosticsReceived((LspServer) lspClient, file);
    }

    @Override
    default void documentLinksReceived(LspClient lspClient, VirtualFile file) {
        documentLinksReceived((LspServer) lspClient, file);
    }
}

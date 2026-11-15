// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import org.eclipse.lsp4j.InitializeResult;

/**
 * Plugins can register their {@link LspServerListener} by overriding {@link LspClientDescriptor#getLspServerListener()}.
 * <p>
 * The functions are called in a background thread, without a read lock.
 */
public interface LspServerListener {
    /**
     * Once the IDE receives a response from the LSP server to the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#initialize">initialize</a> request,
     * it sends the <a href="https://microsoft.github.io/language-server-protocol/specification/#initialized">initialized</a>
     * notification to the server and calls this function.
     */
    default void serverInitialized(InitializeResult params) {
    }

    /**
     * Normal shutdown examples:
     * <ul>
     * <li>the project is being closed</li>
     * <li>the 'Restart' button has been clicked in the Language Services status bar widget</li>
     * </ul>
     * <p>
     * Unexpected shutdown examples:
     * <ul>
     * <li>the response to the <a href="https://microsoft.github.io/language-server-protocol/specification/#initialize">initialize</a>
     * request has not arrived</li>
     * <li>the LSP server process has terminated</li>
     * </ul>
     */
    default void serverStopped(boolean shutdownNormally) {
    }
}

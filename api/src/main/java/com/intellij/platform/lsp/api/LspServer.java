// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

/**
 * Renamed to {@link LspClient}: in <a href="https://microsoft.github.io/language-server-protocol/">LSP terminology</a> the IDE is the client, not the
 * server. Kept as a deprecated marker subtype of {@link LspClient} at its original FQN so that existing consumers and casts keep working;
 * every running instance still implements {@code LspServer}.
 *
 * @deprecated Renamed to {@link LspClient}
 */
@Deprecated
public interface LspServer extends LspClient {
    // There is no covariant `getDescriptor(): LspServerDescriptor`: it cannot be implemented for a client started with
    // a plain LspClientDescriptor. Callers cast: `(LspServerDescriptor) lspServer.getDescriptor()`.

    /**
     * @deprecated Use {@link LspClient#DEFAULT_REQUEST_TIMEOUT_MS}
     */
    @Deprecated
    int DEFAULT_REQUEST_TIMEOUT_MS = LspClient.DEFAULT_REQUEST_TIMEOUT_MS;
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

/**
 * An {@link LspIntegrationProvider} whose descriptors are separate servers.
 * Each descriptor passed to {@link LspIntegrationProvider.LspClientStarter#ensureClientStarted} gets its own {@link LspClient},
 * also when several descriptors have the same roots and support the same file.
 * Two descriptors are the same server when they have the same class, {@link LspClientDescriptor#getPresentableName()}, and {@link LspClientDescriptor#getRoots()}.
 * <p>
 * {@link #fileOpened} is called for each opened file, also when the running clients of the provider already support the file.
 * <p>
 * This is for the servers that a user adds in {@code Settings | Languages & Frameworks | LSP Servers}, where each entry is a separate server.
 * A plugin implements {@link LspIntegrationProvider} instead, and registers one provider per server.
 */
public interface LspBulkIntegrationProvider extends LspIntegrationProvider {
}

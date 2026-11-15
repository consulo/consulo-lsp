// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import org.eclipse.lsp4j.services.LanguageServer;

/**
 * An alias for the {@link org.eclipse.lsp4j.services.LanguageServer} interface from the {@code lsp4j} library.
 * It helps to distinguish between {@code lsp4j} library-specific classes and IntelliJ LSP API classes.
 * <p>
 * For example, the {@link LspClient} class represents an IntelliJ's model of a client connecting to an LSP server,
 * whereas {@link Lsp4jServer} is a lower-level library-specific interface, used to call RPC-style methods on a server.
 * <p>
 * Plugins need to override {@link Lsp4jServer} only if they need to send custom undocumented notifications or requests to the LSP server.
 * <p>
 * Consulo: upstream declares a Kotlin {@code typealias}, which Java doesn't have, so this is an empty sub-interface.
 * The {@code lsp4j} proxy for {@link LspClientDescriptor#getLsp4jServerClass()} implements it.
 *
 * @see LspClientDescriptor#getLsp4jServerClass()
 */
public interface Lsp4jServer extends LanguageServer {
}

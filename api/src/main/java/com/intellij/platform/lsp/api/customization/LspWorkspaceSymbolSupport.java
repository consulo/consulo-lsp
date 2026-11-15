// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspServer;
import consulo.navigation.NavigationItem;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.jspecify.annotations.Nullable;

/**
 * See LSP specification: <a href="https://microsoft.github.io/language-server-protocol/specification/#workspace_symbol">Workspace Symbol</a>
 *
 * @see LspSymbolKindCustomizer
 */
public non-sealed class LspWorkspaceSymbolSupport extends LspWorkspaceSymbolCustomizer {
    /**
     * Creates a NavigationItem for an LSP Workspace Symbol entry that will be displayed in Search Everywhere / Go To Class / Go To Symbol.
     * <p>
     * The default implementation produces an instance of
     * {@link LspWorkspaceSymbolNavigationItem}.
     * Implementors may override this method to customize the item’s presentation, icon, or navigation target
     * (e.g., to return a custom NavigationItem subtype).
     * <p>
     * Important: If you override this method and return a custom NavigationItem, make sure the platform can correctly de-duplicate results.
     * Provide a corresponding {@code SEResultsEqualityProvider} for your items, or reuse an
     * existing one. See {@code com.intellij.platform.lsp.impl.features.workspaceSymbol.LspWorkspaceSymbolEqualityProvider} for the default equality
     * implementation used with LSP workspace symbols.
     *
     * @return a NavigationItem to show in Search Everywhere / Go To Class / Go To Symbol, or null to skip this symbol
     */
    @SuppressWarnings("deprecation")
    public @Nullable NavigationItem createNavigationItem(LspClient lspClient, WorkspaceSymbol workspaceSymbol) {
        return createNavigationItem((LspServer) lspClient, workspaceSymbol);
    }

    /**
     * @deprecated Override or call {@link #createNavigationItem(LspClient, WorkspaceSymbol)} — the LspClient overload
     */
    @Deprecated
    @SuppressWarnings("deprecation")
    public @Nullable NavigationItem createNavigationItem(LspServer lspServer, WorkspaceSymbol workspaceSymbol) {
        return new LspWorkspaceSymbolNavigationItem(lspServer, workspaceSymbol);
    }
}

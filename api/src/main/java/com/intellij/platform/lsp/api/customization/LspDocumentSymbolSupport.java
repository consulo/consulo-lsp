// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

/**
 * Handles <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_documentSymbol">textDocument/documentSymbol</a> requests.
 *
 * @see LspSymbolKindCustomizer
 */
public non-sealed class LspDocumentSymbolSupport extends LspDocumentSymbolCustomizer {
    /**
     * Controls the Structure tool window &amp; File Structure popup.
     */
    @Override
    public boolean getStructureViewSupport() {
        return true;
    }

    /**
     * Controls breadcrumbs, but also sticky lines, diff viewer headers and items in the Recent Locations popup.
     * It also controls the members in the navigation bar when "Show members in navigation bar" is on.
     */
    @Override
    public boolean getBreadcrumbsSupport() {
        return true;
    }
}

// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

import consulo.virtualFileSystem.VirtualFile;

/**
 * See LSP specification: <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_prepareTypeHierarchy">Prepare Type Hierarchy</a>
 *
 * @see LspSymbolKindCustomizer
 */
public non-sealed class LspTypeHierarchySupport extends LspTypeHierarchyCustomizer {
    /**
     * Determines whether type hierarchy functionality should be requested from the LSP server for the given file.
     *
     * @param file the file to check for type hierarchy support
     * @return true if type hierarchy should be requested for this file, false otherwise
     */
    public boolean shouldAskServerForTypeHierarchy(VirtualFile file) {
        return true;
    }
}

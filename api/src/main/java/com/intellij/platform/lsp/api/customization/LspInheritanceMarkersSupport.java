// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.SymbolKind;

public non-sealed class LspInheritanceMarkersSupport extends LspInheritanceMarkersCustomizer {
    /**
     * Returns true if the IDE should ask the server for inheritance markers for the given file.
     * <p>
     * One pull costs one {@code documentSymbol} request plus one request per queried symbol.
     * Override this method to limit the feature to files where the server answers fast.
     */
    public boolean shouldAskServerForMarkers(VirtualFile file) {
        return true;
    }

    /**
     * A method-like symbol gets an "implemented/overridden by" marker from the {@code textDocument/implementation} request.
     */
    public boolean isMethodLikeSymbol(SymbolKind kind) {
        return kind == SymbolKind.Method || kind == SymbolKind.Function;
    }

    /**
     * A class-like symbol gets a "subtypes" marker from the {@code typeHierarchy/subtypes} request.
     */
    public boolean isClassLikeSymbol(SymbolKind kind) {
        return kind == SymbolKind.Class || kind == SymbolKind.Interface || kind == SymbolKind.Struct;
    }
}

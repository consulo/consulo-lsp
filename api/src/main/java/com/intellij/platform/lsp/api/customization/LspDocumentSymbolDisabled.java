// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

public final class LspDocumentSymbolDisabled extends LspDocumentSymbolCustomizer {
    public static final LspDocumentSymbolDisabled INSTANCE = new LspDocumentSymbolDisabled();

    private LspDocumentSymbolDisabled() {
    }

    @Override
    public boolean getStructureViewSupport() {
        return false;
    }

    @Override
    public boolean getBreadcrumbsSupport() {
        return false;
    }
}

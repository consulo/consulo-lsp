// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

import com.intellij.platform.lsp.api.LspServerDescriptor;

/**
 * Delegates to previously overridden properties on {@link LspServerDescriptor}. Clients can continue to use the old API by using this class.
 */
@SuppressWarnings("deprecation")
public class DeprecatedLspCustomization extends LspCustomization {
    private static final LspCustomization DEFAULT_LSP_CUSTOMIZATION = new LspCustomization();

    private final LspServerDescriptor myDescriptor;

    public DeprecatedLspCustomization(LspServerDescriptor descriptor) {
        myDescriptor = descriptor;
    }

    /**
     * Upstream: the internal top-level {@code val defaultLspCustomization} of {@code DeprecatedLspCustomization.kt}.
     */
    public static LspCustomization getDefaultLspCustomization() {
        return DEFAULT_LSP_CUSTOMIZATION;
    }

    @Override
    public LspGoToDefinitionCustomizer getGoToDefinitionCustomizer() {
        return myDescriptor.getLspGoToDefinitionSupport() ? getDefaultLspCustomization().getGoToDefinitionCustomizer() : LspGoToDefinitionDisabled.INSTANCE;
    }

    @Override
    public LspGoToTypeDefinitionCustomizer getGoToTypeDefinitionCustomizer() {
        return myDescriptor.getLspGoToTypeDefinitionSupport() ? getDefaultLspCustomization().getGoToTypeDefinitionCustomizer() : LspGoToTypeDefinitionDisabled.INSTANCE;
    }

    @Override
    public LspHoverCustomizer getHoverCustomizer() {
        return myDescriptor.getLspHoverSupport() ? getDefaultLspCustomization().getHoverCustomizer() : LspHoverDisabled.INSTANCE;
    }

    @Override
    public LspCompletionCustomizer getCompletionCustomizer() {
        LspCompletionSupport support = myDescriptor.getLspCompletionSupport();
        return support != null ? support : LspCompletionDisabled.INSTANCE;
    }

    @Override
    public LspSemanticTokensCustomizer getSemanticTokensCustomizer() {
        LspSemanticTokensSupport support = myDescriptor.getLspSemanticTokensSupport();
        return support != null ? support : LspSemanticTokensDisabled.INSTANCE;
    }

    @Override
    public LspDiagnosticsCustomizer getDiagnosticsCustomizer() {
        LspDiagnosticsSupport support = myDescriptor.getLspDiagnosticsSupport();
        return support != null ? support : LspDiagnosticsDisabled.INSTANCE;
    }

    @Override
    public LspCodeActionsCustomizer getCodeActionsCustomizer() {
        LspCodeActionsSupport support = myDescriptor.getLspCodeActionsSupport();
        return support != null ? support : LspCodeActionsDisabled.INSTANCE;
    }

    @Override
    public LspCommandsCustomizer getCommandsCustomizer() {
        LspCommandsSupport support = myDescriptor.getLspCommandsSupport();
        return support != null ? support : LspCommandsDisabled.INSTANCE;
    }

    @Override
    public LspFormattingCustomizer getFormattingCustomizer() {
        LspFormattingSupport support = myDescriptor.getLspFormattingSupport();
        return support != null ? support : LspFormattingDisabled.INSTANCE;
    }

    @Override
    public LspFindReferencesCustomizer getFindReferencesCustomizer() {
        LspFindReferencesSupport support = myDescriptor.getLspFindReferencesSupport();
        return support != null ? support : LspFindReferencesDisabled.INSTANCE;
    }

    @Override
    public LspDocumentColorCustomizer getDocumentColorCustomizer() {
        LspDocumentColorSupport support = myDescriptor.getLspDocumentColorSupport();
        return support != null ? support : LspDocumentColorDisabled.INSTANCE;
    }

    @Override
    public LspDocumentLinkCustomizer getDocumentLinkCustomizer() {
        LspDocumentLinkSupport support = myDescriptor.getLspDocumentLinkSupport();
        return support != null ? support : LspDocumentLinkDisabled.INSTANCE;
    }
}

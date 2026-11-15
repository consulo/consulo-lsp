// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import com.intellij.platform.lsp.api.customization.DeprecatedLspCustomization;
import com.intellij.platform.lsp.api.customization.LspCodeActionsSupport;
import com.intellij.platform.lsp.api.customization.LspCommandsSupport;
import com.intellij.platform.lsp.api.customization.LspCompletionSupport;
import com.intellij.platform.lsp.api.customization.LspCustomization;
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport;
import com.intellij.platform.lsp.api.customization.LspDocumentColorSupport;
import com.intellij.platform.lsp.api.customization.LspDocumentLinkSupport;
import com.intellij.platform.lsp.api.customization.LspFindReferencesSupport;
import com.intellij.platform.lsp.api.customization.LspFormattingSupport;
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionSupport;
import com.intellij.platform.lsp.api.customization.LspGoToTypeDefinitionSupport;
import com.intellij.platform.lsp.api.customization.LspHoverSupport;
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport;
import consulo.logging.Logger;
import consulo.lsp.compat.OSProcessHandler;
import consulo.process.ExecutionException;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

import static com.intellij.platform.lsp.api.customization.DeprecatedLspCustomization.getDefaultLspCustomization;

/**
 * @deprecated Use {@link LspClientDescriptor}
 */
@Deprecated
public abstract class LspServerDescriptor extends LspClientDescriptor {
    /**
     * @deprecated Use {@link LspClientDescriptor#LOG}
     */
    @Deprecated
    public static final Logger LOG = LspClientDescriptor.LOG;

    // Consulo: the deprecated companion `getLanguageId(file)` is not ported, in Java it would clash with the inherited instance
    // function `getLanguageId(VirtualFile)`. Use LspClientDescriptor.getDefaultLanguageId(file).

    private final LspCustomization myLspCustomization = new DeprecatedLspCustomization(this);

    //<editor-fold desc="Deprecated stuff.">
    private final boolean myLspGoToDefinitionSupport = getDefaultLspCustomization().getGoToDefinitionCustomizer() instanceof LspGoToDefinitionSupport;
    private final boolean myLspGoToTypeDefinitionSupport = getDefaultLspCustomization().getGoToTypeDefinitionCustomizer() instanceof LspGoToTypeDefinitionSupport;
    private final boolean myLspHoverSupport = getDefaultLspCustomization().getHoverCustomizer() instanceof LspHoverSupport;
    private final @Nullable LspCompletionSupport myLspCompletionSupport = getDefaultLspCustomization().getCompletionCustomizer() instanceof LspCompletionSupport support ? support : null;
    private final @Nullable LspSemanticTokensSupport myLspSemanticTokensSupport = getDefaultLspCustomization().getSemanticTokensCustomizer() instanceof LspSemanticTokensSupport support ? support : null;
    private final @Nullable LspDiagnosticsSupport myLspDiagnosticsSupport = getDefaultLspCustomization().getDiagnosticsCustomizer() instanceof LspDiagnosticsSupport support ? support : null;
    private final @Nullable LspCodeActionsSupport myLspCodeActionsSupport = getDefaultLspCustomization().getCodeActionsCustomizer() instanceof LspCodeActionsSupport support ? support : null;
    private final @Nullable LspCommandsSupport myLspCommandsSupport = getDefaultLspCustomization().getCommandsCustomizer() instanceof LspCommandsSupport support ? support : null;
    private final @Nullable LspFormattingSupport myLspFormattingSupport = getDefaultLspCustomization().getFormattingCustomizer() instanceof LspFormattingSupport support ? support : null;
    private final @Nullable LspFindReferencesSupport myLspFindReferencesSupport = getDefaultLspCustomization().getFindReferencesCustomizer() instanceof LspFindReferencesSupport support ? support : null;
    private final @Nullable LspDocumentColorSupport myLspDocumentColorSupport = getDefaultLspCustomization().getDocumentColorCustomizer() instanceof LspDocumentColorSupport support ? support : null;
    private final @Nullable LspDocumentLinkSupport myLspDocumentLinkSupport = getDefaultLspCustomization().getDocumentLinkCustomizer() instanceof LspDocumentLinkSupport support ? support : null;
    //</editor-fold>

    protected LspServerDescriptor(Project project, String presentableName, VirtualFile... roots) {
        super(project, presentableName, roots);
    }

    /**
     * Called in a background thread.
     */
    @Override
    public OSProcessHandler startServerProcess() throws ExecutionException {
        return (OSProcessHandler) super.startServerProcess();
    }

    @Override
    public LspCustomization getLspCustomization() {
        return myLspCustomization;
    }

    //<editor-fold desc="Deprecated stuff.">

    /**
     * @see LspCustomization#getGoToDefinitionCustomizer()
     * @deprecated Use {@code getLspCustomization().getGoToDefinitionCustomizer() instanceof LspGoToDefinitionSupport} instead
     */
    @Deprecated
    public boolean getLspGoToDefinitionSupport() {
        if (!(getLspCustomization() instanceof DeprecatedLspCustomization)) {
            return getLspCustomization().getGoToDefinitionCustomizer() instanceof LspGoToDefinitionSupport;
        }
        return myLspGoToDefinitionSupport;
    }

    /**
     * @see LspCustomization#getGoToTypeDefinitionCustomizer()
     * @deprecated Use {@code getLspCustomization().getGoToTypeDefinitionCustomizer() instanceof LspGoToTypeDefinitionSupport} instead
     */
    @Deprecated
    public boolean getLspGoToTypeDefinitionSupport() {
        if (!(getLspCustomization() instanceof DeprecatedLspCustomization)) {
            return getLspCustomization().getGoToTypeDefinitionCustomizer() instanceof LspGoToTypeDefinitionSupport;
        }
        return myLspGoToTypeDefinitionSupport;
    }

    /**
     * @see LspCustomization#getHoverCustomizer()
     * @deprecated Use {@code getLspCustomization().getHoverCustomizer() instanceof LspHoverSupport} instead
     */
    @Deprecated
    public boolean getLspHoverSupport() {
        if (!(getLspCustomization() instanceof DeprecatedLspCustomization)) {
            return getLspCustomization().getHoverCustomizer() instanceof LspHoverSupport;
        }
        return myLspHoverSupport;
    }

    /**
     * @see LspCustomization#getCompletionCustomizer()
     * @deprecated Use {@code getLspCustomization().getCompletionCustomizer()} instead
     */
    @Deprecated
    public @Nullable LspCompletionSupport getLspCompletionSupport() {
        if (!(getLspCustomization() instanceof DeprecatedLspCustomization)) {
            return getLspCustomization().getCompletionCustomizer() instanceof LspCompletionSupport support ? support : null;
        }
        return myLspCompletionSupport;
    }

    /**
     * @see LspCustomization#getSemanticTokensCustomizer()
     * @deprecated Use {@code getLspCustomization().getSemanticTokensCustomizer()} instead
     */
    @Deprecated
    public @Nullable LspSemanticTokensSupport getLspSemanticTokensSupport() {
        if (!(getLspCustomization() instanceof DeprecatedLspCustomization)) {
            return getLspCustomization().getSemanticTokensCustomizer() instanceof LspSemanticTokensSupport support ? support : null;
        }
        return myLspSemanticTokensSupport;
    }

    /**
     * @see LspCustomization#getDiagnosticsCustomizer()
     * @deprecated Use {@code getLspCustomization().getDiagnosticsCustomizer()} instead
     */
    @Deprecated
    public @Nullable LspDiagnosticsSupport getLspDiagnosticsSupport() {
        if (!(getLspCustomization() instanceof DeprecatedLspCustomization)) {
            return getLspCustomization().getDiagnosticsCustomizer() instanceof LspDiagnosticsSupport support ? support : null;
        }
        return myLspDiagnosticsSupport;
    }

    /**
     * @see LspCustomization#getCodeActionsCustomizer()
     * @deprecated Use {@code getLspCustomization().getCodeActionsCustomizer()} instead
     */
    @Deprecated
    public @Nullable LspCodeActionsSupport getLspCodeActionsSupport() {
        if (!(getLspCustomization() instanceof DeprecatedLspCustomization)) {
            return getLspCustomization().getCodeActionsCustomizer() instanceof LspCodeActionsSupport support ? support : null;
        }
        return myLspCodeActionsSupport;
    }

    /**
     * @see LspCustomization#getCommandsCustomizer()
     * @deprecated Use {@code getLspCustomization().getCommandsCustomizer()} instead
     */
    @Deprecated
    public @Nullable LspCommandsSupport getLspCommandsSupport() {
        if (!(getLspCustomization() instanceof DeprecatedLspCustomization)) {
            return getLspCustomization().getCommandsCustomizer() instanceof LspCommandsSupport support ? support : null;
        }
        return myLspCommandsSupport;
    }

    /**
     * @see LspCustomization#getFormattingCustomizer()
     * @deprecated Use {@code getLspCustomization().getFormattingCustomizer()} instead
     */
    @Deprecated
    public @Nullable LspFormattingSupport getLspFormattingSupport() {
        if (!(getLspCustomization() instanceof DeprecatedLspCustomization)) {
            return getLspCustomization().getFormattingCustomizer() instanceof LspFormattingSupport support ? support : null;
        }
        return myLspFormattingSupport;
    }

    /**
     * @see LspCustomization#getFindReferencesCustomizer()
     * @deprecated Use {@code getLspCustomization().getFindReferencesCustomizer()} instead
     */
    @Deprecated
    public @Nullable LspFindReferencesSupport getLspFindReferencesSupport() {
        if (!(getLspCustomization() instanceof DeprecatedLspCustomization)) {
            return getLspCustomization().getFindReferencesCustomizer() instanceof LspFindReferencesSupport support ? support : null;
        }
        return myLspFindReferencesSupport;
    }

    /**
     * @see LspCustomization#getDocumentColorCustomizer()
     * @deprecated Use {@code getLspCustomization().getDocumentColorCustomizer()} instead
     */
    @Deprecated
    public @Nullable LspDocumentColorSupport getLspDocumentColorSupport() {
        if (!(getLspCustomization() instanceof DeprecatedLspCustomization)) {
            return getLspCustomization().getDocumentColorCustomizer() instanceof LspDocumentColorSupport support ? support : null;
        }
        return myLspDocumentColorSupport;
    }

    /**
     * @see LspCustomization#getDocumentLinkCustomizer()
     * @deprecated Use {@code getLspCustomization().getDocumentLinkCustomizer()} instead
     */
    @Deprecated
    public @Nullable LspDocumentLinkSupport getLspDocumentLinkSupport() {
        if (!(getLspCustomization() instanceof DeprecatedLspCustomization)) {
            return getLspCustomization().getDocumentLinkCustomizer() instanceof LspDocumentLinkSupport support ? support : null;
        }
        return myLspDocumentLinkSupport;
    }

    //</editor-fold>
}

package com.intellij.platform.lsp.impl.features.highlighting;

import com.intellij.platform.lsp.api.customization.LspCodeActionsCustomizer;
import com.intellij.platform.lsp.api.customization.LspCodeActionsSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.impl.features.quickFix.LspQuickFixSet;
import consulo.language.editor.intention.IntentionAction;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.jspecify.annotations.Nullable;

import java.util.List;

public class LspDiagnosticAndLazyQuickFixes {
    private final Diagnostic myDiagnostic;
    private final @Nullable TextDocumentIdentifier myDocumentId;
    private volatile @Nullable List<IntentionAction> myQuickFixes = null;

    public LspDiagnosticAndLazyQuickFixes(Diagnostic diagnostic) {
        this(diagnostic, null);
    }

    public LspDiagnosticAndLazyQuickFixes(Diagnostic diagnostic, @Nullable TextDocumentIdentifier documentId) {
        myDiagnostic = diagnostic;
        myDocumentId = documentId;
    }

    public Diagnostic getDiagnostic() {
        return myDiagnostic;
    }

    public @Nullable TextDocumentIdentifier getDocumentId() {
        return myDocumentId;
    }

    public List<IntentionAction> getQuickFixes(LspClientImpl lspClient, VirtualFile file) {
        List<IntentionAction> cached = myQuickFixes;
        if (cached != null) {
            return cached;
        }

        LspCodeActionsCustomizer codeActionsCustomizer = lspClient.getDescriptor().getLspCustomization().getCodeActionsCustomizer();
        boolean clientSupportsQuickFixes = codeActionsCustomizer instanceof LspCodeActionsSupport support && support.getQuickFixesSupport();
        if (!clientSupportsQuickFixes) {
            return List.of();
        }
        boolean serverSupportsQuickFixes = LspServerCapabilitiesKt.supportsCodeActions(
            lspClient,
            kinds -> kinds.stream().anyMatch(kind -> kind.startsWith(CodeActionKind.QuickFix))
        );
        if (!serverSupportsQuickFixes) {
            return List.of();
        }

        LspQuickFixSet quickFixSet = new LspQuickFixSet(lspClient, file, myDiagnostic, myDocumentId);
        List<IntentionAction> result = quickFixSet.getQuickFixes();
        myQuickFixes = result;
        return result;
    }

    public static Diagnostic copyDiagnosticWithRange(Diagnostic diagnostic, Range range) {
        Diagnostic result = new Diagnostic();
        result.setRange(range);
        result.setSeverity(diagnostic.getSeverity());
        result.setCode(diagnostic.getCode());
        result.setCodeDescription(diagnostic.getCodeDescription());
        result.setSource(diagnostic.getSource());
        result.setMessage(diagnostic.getMessage());
        result.setTags(diagnostic.getTags());
        result.setRelatedInformation(diagnostic.getRelatedInformation());
        result.setData(diagnostic.getData());
        return result;
    }
}

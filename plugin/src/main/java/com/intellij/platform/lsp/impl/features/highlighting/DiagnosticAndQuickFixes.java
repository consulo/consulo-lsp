package com.intellij.platform.lsp.impl.features.highlighting;

import com.intellij.platform.lsp.impl.LspClientImpl;
import consulo.language.editor.intention.IntentionAction;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.jspecify.annotations.Nullable;

import java.util.List;

public final class DiagnosticAndQuickFixes {
    private final Diagnostic myDiagnostic;
    private final List<IntentionAction> myQuickFixes;

    public DiagnosticAndQuickFixes(Diagnostic diagnostic, List<IntentionAction> quickFixes) {
        myDiagnostic = diagnostic;
        myQuickFixes = quickFixes;
    }

    public Diagnostic getDiagnostic() {
        return myDiagnostic;
    }

    public List<IntentionAction> getQuickFixes() {
        return myQuickFixes;
    }

    public static DiagnosticAndQuickFixes build(
        LspClientImpl client,
        VirtualFile virtualFile,
        Diagnostic diagnostic,
        TextDocumentIdentifier documentId
    ) {
        return new DiagnosticAndQuickFixes(
            diagnostic,
            new LspDiagnosticAndLazyQuickFixes(diagnostic, documentId).getQuickFixes(client, virtualFile)
        );
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o
            || o instanceof DiagnosticAndQuickFixes that
            && myDiagnostic.equals(that.myDiagnostic)
            && myQuickFixes.equals(that.myQuickFixes);
    }

    @Override
    public int hashCode() {
        return 31 * myDiagnostic.hashCode() + myQuickFixes.hashCode();
    }

    @Override
    public String toString() {
        return "DiagnosticAndQuickFixes(diagnostic=" + myDiagnostic + ", quickFixes=" + myQuickFixes + ")";
    }
}

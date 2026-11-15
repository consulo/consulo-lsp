// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.colorScheme.TextAttributes;
import consulo.document.Document;
import consulo.document.util.TextRange;
import consulo.language.editor.annotation.AnnotationBuilder;
import consulo.language.editor.annotation.AnnotationHolder;
import consulo.language.editor.annotation.HighlightSeverity;
import consulo.language.editor.inspection.ProblemHighlightType;
import consulo.language.editor.intention.IntentionAction;
import consulo.language.psi.PsiDocumentManager;
import consulo.language.psi.PsiFile;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Handles <a href="https://microsoft.github.io/language-server-protocol/specification#diagnostic">Diagnostic</a> objects received from the LSP server.
 */
public non-sealed class LspDiagnosticsSupport extends LspDiagnosticsCustomizer {
    /**
     * {@code true} means that the IDE should send the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_pullDiagnostics">textDocument/diagnostic</a>
     * request to the server for the given file.
     * Diagnostics received in response will be passed to {@link #createAnnotation}.
     * <p>
     * This function is called only if the server has the corresponding capability.
     * <p>
     * Note that the LSP specification describes two ways of getting diagnostic information for a file: pushed by the server
     * (<a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_publishDiagnostics">textDocument/publishDiagnostics</a>)
     * and pulled by the client
     * (<a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_pullDiagnostics">textDocument/diagnostic</a>).
     * This function controls the latter one.
     */
    public boolean shouldAskServerForDiagnostics(VirtualFile file) {
        return true;
    }

    /**
     * Called in a background thread.
     */
    @RequiredReadAction
    public void createAnnotation(AnnotationHolder holder, Diagnostic diagnostic, TextRange textRange, List<IntentionAction> quickFixes) {
        HighlightSeverity severity = getHighlightSeverity(diagnostic);
        if (severity == null) {
            return;
        }
        AnnotationBuilder builder = holder.newAnnotation(severity, getMessage(diagnostic))
            .tooltip(getTooltip(diagnostic))
            .range(textRange);

        // To get correct error highlighting in the editor, it's necessary to call `afterEndOfLine()` for zero-length diagnostics at the end of a line
        if (textRange.isEmpty()) {
            PsiFile psiFile = holder.getCurrentAnnotationSession().getFile();
            Document document = PsiDocumentManager.getInstance(psiFile.getProject()).getDocument(psiFile);
            if (document != null) {
                int lineNumber = document.getLineNumber(textRange.getStartOffset());
                int lineEndOffset = document.getLineEndOffset(lineNumber);
                if (textRange.getStartOffset() == lineEndOffset) {
                    builder = builder.afterEndOfLine();
                }
            }
        }

        ProblemHighlightType highlightType = getSpecialHighlightType(diagnostic);
        if (highlightType != null) {
            builder = builder.highlightType(highlightType);
        }

        TextAttributes textAttributes = getEnforcedTextAttributes(diagnostic);
        if (textAttributes != null) {
            builder = builder.enforcedTextAttributes(textAttributes);
        }

        for (IntentionAction quickFix : customizeQuickFixes(diagnostic, quickFixes)) {
            builder = builder.withFix(quickFix);
        }

        builder.create();
    }

    /**
     * Implementations may return {@code null} if this {@code diagnostic} should be ignored.
     */
    public @Nullable HighlightSeverity getHighlightSeverity(Diagnostic diagnostic) {
        DiagnosticSeverity severity = diagnostic.getSeverity();
        if (severity == DiagnosticSeverity.Error) {
            return HighlightSeverity.ERROR;
        }
        if (severity == DiagnosticSeverity.Warning) {
            return HighlightSeverity.WARNING;
        }
        return HighlightSeverity.WEAK_WARNING;
    }

    public String getMessage(Diagnostic diagnostic) {
        return Lsp4jUtilKt.getMessageIfStringOrEmpty(diagnostic);
    }

    public String getTooltip(Diagnostic diagnostic) {
        return Lsp4jUtilKt.getMessageIfStringOrEmpty(diagnostic);
    }

    public @Nullable ProblemHighlightType getSpecialHighlightType(Diagnostic diagnostic) {
        List<DiagnosticTag> tags = diagnostic.getTags();
        if (tags != null && tags.contains(DiagnosticTag.Unnecessary)) {
            return ProblemHighlightType.LIKE_UNUSED_SYMBOL;
        }
        if (tags != null && tags.contains(DiagnosticTag.Deprecated)) {
            return ProblemHighlightType.LIKE_DEPRECATED;
        }
        return null;
    }

    public @Nullable TextAttributes getEnforcedTextAttributes(Diagnostic diagnostic) {
        return null;
    }

    public List<IntentionAction> customizeQuickFixes(Diagnostic diagnostic, List<IntentionAction> quickFixes) {
        return quickFixes;
    }
}

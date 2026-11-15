// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspServer;
import consulo.language.psi.PsiFile;
import org.eclipse.lsp4j.CodeAction;
import org.jspecify.annotations.Nullable;

/**
 * Handles <a href="https://microsoft.github.io/language-server-protocol/specification#codeAction">CodeAction</a> objects received from the LSP server.
 */
public non-sealed class LspCodeActionsSupport extends LspCodeActionsCustomizer {
    /**
     * Whether the IDE should send
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_codeAction">textDocument/codeAction</a>
     * requests to the LSP server to receive quick fixes for a specific {@link org.eclipse.lsp4j.Diagnostic} (see {@link CodeAction#getDiagnostics()}).
     */
    public boolean getQuickFixesSupport() {
        return true;
    }

    /**
     * Creates quick fix for the specific {@link CodeAction}.
     * Implementations may return {@code null} if they don't want to provide a quick fix for this {@code codeAction}.
     *
     * @param codeAction result of the
     *                   <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_codeAction">textDocument/codeAction</a> request to the
     *                   LSP server.
     *                   The request asked for quick fixes for a specific {@link org.eclipse.lsp4j.Diagnostic} (see {@link CodeAction#getDiagnostics()}).
     */
    @SuppressWarnings("deprecation")
    public @Nullable LspIntentionAction createQuickFix(LspClient lspClient, CodeAction codeAction) {
        return createQuickFix((LspServer) lspClient, codeAction);
    }

    /**
     * @deprecated Override or call {@link #createQuickFix(LspClient, CodeAction)} — the LspClient overload
     */
    @Deprecated
    @SuppressWarnings("deprecation")
    public @Nullable LspIntentionAction createQuickFix(LspServer lspServer, CodeAction codeAction) {
        return new LspIntentionAction(lspServer, codeAction);
    }

    /**
     * Whether the IDE should send
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_codeAction">textDocument/codeAction</a>
     * requests to the LSP server to receive code edit suggestions based on the current text selection and caret position,
     * but not related to a specific {@link org.eclipse.lsp4j.Diagnostic}.
     */
    public boolean getIntentionActionsSupport() {
        return true;
    }

    /**
     * Creates an Intention action for the specific {@link CodeAction}.
     * Implementations may return {@code null} if they don't want to show an Intention action for this {@code codeAction}.
     *
     * @param codeAction result of the
     *                   <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_codeAction">textDocument/codeAction</a> request to the
     *                   LSP server.
     *                   The request asked for code edit suggestions based on the current text selection and caret position,
     *                   but not related to a specific {@link org.eclipse.lsp4j.Diagnostic}.
     */
    @SuppressWarnings("deprecation")
    public @Nullable LspIntentionAction createIntentionAction(LspClient lspClient, CodeAction codeAction) {
        return createIntentionAction((LspServer) lspClient, codeAction);
    }

    /**
     * @deprecated Override or call {@link #createIntentionAction(LspClient, CodeAction)} — the LspClient overload
     */
    @Deprecated
    @SuppressWarnings("deprecation")
    public @Nullable LspIntentionAction createIntentionAction(LspServer lspServer, CodeAction codeAction) {
        return new LspIntentionAction(lspServer, codeAction);
    }

    /**
     * Whether the IDE routes the Inline refactoring to the LSP server.
     * The IDE requests the code actions of the {@code refactor.inline} kind at the caret or the selection and applies the chosen one.
     * The server capabilities gate the request per client, {@link #shouldRunRefactoring} gates it per file.
     */
    public boolean getInlineRefactoringSupport() {
        return true;
    }

    /**
     * Whether the IDE routes the Extract refactorings to the LSP server:
     * Introduce Variable, Introduce Field, Introduce Constant, and Extract Method.
     * The IDE requests the code actions of the {@code refactor.extract.*} kinds at the selection and applies the chosen one.
     * The server capabilities gate the request per client, {@link #shouldRunRefactoring} gates it per file.
     */
    public boolean getExtractRefactoringSupport() {
        return true;
    }

    /**
     * True when the server serves the Inline and Extract refactorings in {@code psiFile}.
     * A plain text or TextMate file has no PSI structure, so no language refactoring handler serves it.
     * A plugin can override this for another file type.
     */
    public boolean shouldRunRefactoring(PsiFile psiFile) {
        String id = psiFile.getLanguage().getID();
        return id.equals("TEXT") || id.equals("textmate");
    }
}

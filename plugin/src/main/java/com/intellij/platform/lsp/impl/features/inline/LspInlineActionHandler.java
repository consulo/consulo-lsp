package com.intellij.platform.lsp.impl.features.inline;

import com.intellij.platform.lsp.api.customization.LspCodeActionsCustomizer;
import com.intellij.platform.lsp.api.customization.LspCodeActionsSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.impl.features.LspPendingClientKt;
import com.intellij.platform.lsp.impl.features.codeActions.LspRefactoringCodeActionsKt;
import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.Application;
import consulo.codeEditor.Editor;
import consulo.language.Language;
import consulo.language.editor.refactoring.inline.InlineActionHandler;
import consulo.language.psi.PsiDocumentManager;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.CodeActionKind;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Consulo: upstream registers this handler with {@code order="last"}, so every language-specific {@link InlineActionHandler} is asked
 * first. Consulo reserves {@code first}/{@code last} for the plugin that owns the extension point, so this handler steps aside
 * instead: it is not enabled on an element that another {@link InlineActionHandler} can inline ({@link #isHandledByOtherHandler}).
 */
@ExtensionImpl
public class LspInlineActionHandler extends InlineActionHandler {

    // `isEnabledOnElement` gates the action for real, the same way the ANY-language Extract providers do.
    @Override
    public boolean isEnabledForLanguage(Language l) {
        return true;
    }

    // an LSP code action needs a caret or a selection in an open document, so the Project view cannot invoke this handler
    @Override
    public boolean canInlineElement(PsiElement element) {
        return false;
    }

    @Override
    @RequiredReadAction
    public boolean isEnabledOnElement(PsiElement element, @Nullable Editor editor) {
        return editor != null && canInlineElementInEditor(element, editor);
    }

    @Override
    @RequiredReadAction
    public boolean canInlineElementInEditor(PsiElement element, Editor editor) {
        PsiFile psiFile = editorPsiFile(element.getProject(), editor);
        if (psiFile == null) {
            return false;
        }
        if (!(!findLspClientsForInline(psiFile).isEmpty() || findPendingLspClientForInline(psiFile) != null)) {
            return false;
        }
        return !isHandledByOtherHandler(element, editor);
    }

    @Override
    @RequiredUIAccess
    public void inlineElement(Project project, @Nullable Editor editor, PsiElement element) {
        if (editor == null) {
            return;
        }
        PsiFile psiFile = editorPsiFile(project, editor);
        if (psiFile == null) {
            return;
        }
        VirtualFile virtualFile = psiFile.getVirtualFile();
        if (virtualFile == null) {
            return;
        }
        List<LspClientImpl> lspClients = findLspClientsForInline(psiFile);
        if (lspClients.isEmpty()) {
            // A client can still be starting when the shortcut fires, so tell the user instead of a silent no-op.
            LspClientImpl pendingClient = findPendingLspClientForInline(psiFile);
            if (pendingClient != null) {
                LspPendingClientKt.showLspServerNotReadyHint(editor, pendingClient);
            }
            return;
        }
        LspRefactoringCodeActionsKt.requestAndApplyCodeActions(
            lspClients, editor, virtualFile,
            List.of(CodeActionKind.RefactorInline),
            LspLocalize.lspInlineProgressTitle(),
            LspLocalize.lspInlineNoAvailableActions(),
            LspLocalize.lspInlinePopupTitle()
        );
    }

    // The platform hands over the resolved target, which can be a declaration in another file.
    // The code action runs at the caret, so the editor's document owns both the gate and the request.
    private static @Nullable PsiFile editorPsiFile(Project project, Editor editor) {
        return PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
    }

    /**
     * Consulo only: replaces upstream {@code order="last"}, see the class Javadoc.
     */
    @RequiredReadAction
    private boolean isHandledByOtherHandler(PsiElement element, Editor editor) {
        return Application.get().getExtensionPoint(InlineActionHandler.class).anyMatchSafe(handler ->
            handler != this && !(handler instanceof LspInlineActionHandler) && handler.canInlineElementInEditor(element, editor)
        );
    }

    public static List<LspClientImpl> findLspClientsForInline(PsiFile psiFile) {
        VirtualFile virtualFile = psiFile.getVirtualFile();
        if (virtualFile == null) {
            return List.of();
        }
        List<LspClientImpl> result = new ArrayList<>();
        for (LspClientImpl client : LspClientManagerImpl.getInstanceImpl(psiFile.getProject()).getClientsWithThisFileOpen(virtualFile)) {
            if (optsInToInline(client, psiFile)
                && LspServerCapabilitiesKt.supportsCodeActionsOfKind(client, virtualFile, CodeActionKind.RefactorInline)) {
                result.add(client);
            }
        }
        return result;
    }

    public static @Nullable LspClientImpl findPendingLspClientForInline(PsiFile psiFile) {
        return LspPendingClientKt.findPendingLspClient(psiFile, it -> optsInToInline(it, psiFile));
    }

    private static boolean optsInToInline(LspClientImpl lspClient, PsiFile psiFile) {
        LspCodeActionsCustomizer customizer = lspClient.getDescriptor().getLspCustomization().getCodeActionsCustomizer();
        return customizer instanceof LspCodeActionsSupport support
            && support.getInlineRefactoringSupport()
            && support.shouldRunRefactoring(psiFile);
    }
}

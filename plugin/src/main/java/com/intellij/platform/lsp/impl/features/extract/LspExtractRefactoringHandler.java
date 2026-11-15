package com.intellij.platform.lsp.impl.features.extract;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.features.LspPendingClientKt;
import com.intellij.platform.lsp.impl.features.codeActions.LspRefactoringCodeActionsKt;
import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.codeEditor.Editor;
import consulo.dataContext.DataContext;
import consulo.language.editor.refactoring.action.RefactoringActionHandler;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

import java.util.List;

public class LspExtractRefactoringHandler implements RefactoringActionHandler {
    private final List<String> myKinds;

    public LspExtractRefactoringHandler(List<String> kinds) {
        myKinds = kinds;
    }

    @Override
    @RequiredUIAccess
    public void invoke(Project project, @Nullable Editor editor, @Nullable PsiFile file, @Nullable DataContext dataContext) {
        if (editor == null || file == null) {
            return;
        }
        VirtualFile virtualFile = file.getVirtualFile();
        if (virtualFile == null) {
            return;
        }
        List<LspClientImpl> lspClients = LspRefactoringSupportProvider.findLspClientsForExtract(file, myKinds);
        if (lspClients.isEmpty()) {
            // A client can still be starting when the shortcut fires, so tell the user instead of a silent no-op.
            LspClientImpl pendingClient = LspRefactoringSupportProvider.findPendingLspClientForExtract(file);
            if (pendingClient != null) {
                LspPendingClientKt.showLspServerNotReadyHint(editor, pendingClient);
            }
            else {
                LspRefactoringCodeActionsKt.showErrorHint(editor, LspLocalize.lspExtractNoAvailableActions());
            }
            return;
        }
        LspRefactoringCodeActionsKt.requestAndApplyCodeActions(
            lspClients, editor, virtualFile,
            myKinds,
            LspLocalize.lspExtractProgressTitle(),
            LspLocalize.lspExtractNoAvailableActions(),
            LspLocalize.lspExtractPopupTitle()
        );
    }

    @Override
    @RequiredUIAccess
    public void invoke(Project project, PsiElement[] elements, @Nullable DataContext dataContext) {
        // The extract refactorings run from the editor only.
    }
}

package com.intellij.platform.lsp.impl.features;

import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.application.Application;
import consulo.codeEditor.Editor;
import consulo.language.editor.hint.HintManager;
import consulo.language.editor.refactoring.util.CommonRefactoringUtil;
import consulo.language.psi.PsiFile;
import consulo.localize.LocalizeValue;
import consulo.module.content.ProjectFileIndex;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import org.jspecify.annotations.Nullable;

import java.util.function.Predicate;

public final class LspPendingClientKt {
    private LspPendingClientKt() {
    }

    /**
     * Finds a client that is expected to handle {@code psiFile} but is still in the {@link LspServerState#Initializing} state.
     * A refactoring keeps its action enabled for such a client, so the shortcut can report a hint instead of a silent no-op.
     * The client roots must cover the file, so an unrelated client cannot claim it.
     */
    public static @Nullable LspClientImpl findPendingLspClient(PsiFile psiFile, Predicate<LspClientImpl> optsIn) {
        for (LspClientImpl client : LspClientManagerImpl.getInstanceImpl(psiFile.getProject()).getAllClients()) {
            if (client.getState() == LspServerState.Initializing &&
                optsIn.test(client) &&
                isExpectedToHandleFile(client, psiFile)) {
                return client;
            }
        }
        return null;
    }

    /**
     * True when the client is expected to handle {@code psiFile} once the file is open:
     * the file is a local content file, the client roots cover it, and the descriptor supports it.
     */
    private static boolean isExpectedToHandleFile(LspClientImpl client, PsiFile psiFile) {
        VirtualFile file = psiFile.getVirtualFile();
        if (file == null) {
            return false;
        }
        if (!file.isInLocalFileSystem()) {
            return false;
        }
        if (!ProjectFileIndex.getInstance(psiFile.getProject()).isInContent(file)) {
            return false;
        }
        boolean withinRoots = false;
        for (VirtualFile root : client.getDescriptor().getRoots()) {
            if (VirtualFileUtil.isAncestor(root, file, false)) {
                withinRoots = true;
                break;
            }
        }
        return withinRoots && client.getDescriptor().isSupportedFile(file);
    }

    /**
     * Shows an editor hint that the server is still starting, so the refactoring cannot run now.
     * In the unit test mode it throws {@link CommonRefactoringUtil.RefactoringErrorHintException} with the same message,
     * the pattern of {@link CommonRefactoringUtil#showErrorHint}.
     */
    public static void showLspServerNotReadyHint(Editor editor, LspClientImpl lspClient) {
        LocalizeValue message = LspLocalize.lspRefactoringServerStarting(lspClient.getDescriptor().getPresentableName());
        if (Application.get().isUnitTestMode()) {
            throw new CommonRefactoringUtil.RefactoringErrorHintException(message);
        }
        HintManager.getInstance().showErrorHint(
            editor, message,
            editor.getCaretModel().getOffset(), editor.getCaretModel().getOffset(),
            HintManager.ABOVE,
            HintManager.HIDE_BY_ANY_KEY | HintManager.HIDE_BY_TEXT_CHANGE | HintManager.UPDATE_BY_SCROLLING,
            3000
        );
    }
}

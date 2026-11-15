package com.intellij.platform.lsp.impl.features.completion;

import com.intellij.platform.lsp.api.customization.LspCompletionSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import consulo.annotation.component.ExtensionImpl;
import consulo.codeEditor.Editor;
import consulo.language.editor.AutoPopupController;
import consulo.language.editor.action.TypedHandlerDelegate;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.language.psi.PsiFile;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.ServerCapabilities;

import java.util.List;

/**
 * Triggers code completion autopopup if the character typed in the editor is listed in the LSP server capabilities
 * as one of the characters that should trigger code completion
 * (see <a href="https://microsoft.github.io/language-server-protocol/specification/#completionOptions">CompletionOptions.triggerCharacters</a>)
 */
@ExtensionImpl(id = "LspAutoPopupTypedHandler")
public class LspAutoPopupTypedHandler extends TypedHandlerDelegate {
    @Override
    public Result checkAutoPopup(char charTyped, Project project, Editor editor, PsiFile psiFile) {
        if (project.isDefault()) {
            return Result.CONTINUE;
        }

        VirtualFile virtualFile = psiFile.getOriginalFile().getVirtualFile();
        if (virtualFile == null) {
            return Result.CONTINUE;
        }
        VirtualFile file = virtualFile instanceof VirtualFileWindow virtualFileWindow ? virtualFileWindow.getDelegate() : virtualFile;

        for (LspClientImpl client : LspClientManagerImpl.getInstanceImpl(project).getClientsWithThisFileOpen(file)) {
            ServerCapabilities serverCapabilities = client.getServerCapabilities();
            CompletionOptions completionProvider = serverCapabilities != null ? serverCapabilities.getCompletionProvider() : null;
            List<String> triggerCharacters = completionProvider != null ? completionProvider.getTriggerCharacters() : null;
            if (triggerCharacters == null || !triggerCharacters.contains(String.valueOf(charTyped))) {
                continue;
            }
            if (!(client.getDescriptor().getLspCustomization().getCompletionCustomizer() instanceof LspCompletionSupport completionSupport)) {
                continue;
            }
            if (!completionSupport.isTriggerCharacterRespected(charTyped)) {
                continue;
            }

            AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
            return Result.STOP;
        }

        return Result.CONTINUE;
    }
}

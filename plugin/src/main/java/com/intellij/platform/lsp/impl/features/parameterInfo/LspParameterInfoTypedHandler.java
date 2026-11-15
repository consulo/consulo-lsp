package com.intellij.platform.lsp.impl.features.parameterInfo;

import com.intellij.platform.lsp.api.customization.LspSignatureHelpSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.dumb.DumbAware;
import consulo.codeEditor.Editor;
import consulo.language.editor.AutoPopupController;
import consulo.language.editor.action.TypedHandlerDelegate;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.language.psi.PsiFile;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;

import java.util.List;

@ExtensionImpl(id = "LspParameterInfoTypedHandler", order = "last, before LspAutoPopupTypedHandler")
public class LspParameterInfoTypedHandler extends TypedHandlerDelegate implements DumbAware {
    @Override
    public Result checkAutoPopup(char charTyped, Project project, Editor editor, PsiFile file) {
        if (project.isDefault()) {
            return Result.CONTINUE;
        }

        VirtualFile fileVirtualFile = file.getVirtualFile();
        if (fileVirtualFile == null) {
            return Result.CONTINUE;
        }
        VirtualFile virtualFile = fileVirtualFile instanceof VirtualFileWindow virtualFileWindow
            ? virtualFileWindow.getDelegate()
            : fileVirtualFile;

        for (LspClientImpl client : LspClientManagerImpl.getInstanceImpl(project).getClientsWithThisFileOpen(virtualFile)) {
            if (!(client.getDescriptor().getLspCustomization().getSignatureHelpCustomizer() instanceof LspSignatureHelpSupport signatureHelpSupport)) {
                continue;
            }
            List<String> triggerCharacters = LspServerCapabilitiesKt.getSignatureHelpTriggerCharacters(client, fileVirtualFile);
            if (triggerCharacters == null || !triggerCharacters.contains(String.valueOf(charTyped))) {
                continue;
            }
            if (!signatureHelpSupport.isTriggerCharacterRespected(charTyped)) {
                continue;
            }

            AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null);
            return Result.CONTINUE;
        }

        return Result.CONTINUE;
    }
}

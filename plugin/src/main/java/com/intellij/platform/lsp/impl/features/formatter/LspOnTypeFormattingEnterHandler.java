package com.intellij.platform.lsp.impl.features.formatter;

import consulo.annotation.access.RequiredWriteAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.Application;
import consulo.codeEditor.Editor;
import consulo.dataContext.DataContext;
import consulo.language.editor.action.EnterHandlerDelegate;
import consulo.language.editor.action.EnterHandlerDelegateAdapter;
import consulo.language.psi.PsiFile;
import consulo.virtualFileSystem.VirtualFile;

/**
 * {@code first}/{@code last} are reserved for the plugin that owns the extension point, so the delegates registered
 * after this one may still change the document in their {@code postProcessEnter}. The request is therefore started after the
 * Enter action ({@code invokeLater}), when the document has its final state.
 */
@ExtensionImpl(id = "LspOnTypeFormattingEnterHandler")
public class LspOnTypeFormattingEnterHandler extends EnterHandlerDelegateAdapter {

    @Override
    @RequiredWriteAction
    public EnterHandlerDelegate.Result postProcessEnter(PsiFile file, Editor editor, DataContext dataContext) {
        VirtualFile virtualFile = file.getOriginalFile().getVirtualFile();
        if (virtualFile == null) {
            return EnterHandlerDelegate.Result.Continue;
        }
        if (!LspOnTypeFormattingKt.shouldPerformOnTypeFormatting(editor)) {
            return EnterHandlerDelegate.Result.Continue;
        }
        Application.get().invokeLater(() -> {
            if (!editor.isDisposed()) {
                LspOnTypeFormattingKt.performOnTypeFormatting(editor, virtualFile, '\n');
            }
        });
        return EnterHandlerDelegate.Result.Continue;
    }
}

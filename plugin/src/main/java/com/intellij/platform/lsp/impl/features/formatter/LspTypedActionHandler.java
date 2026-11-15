package com.intellij.platform.lsp.impl.features.formatter;

import consulo.annotation.access.RequiredWriteAction;
import consulo.codeEditor.Editor;
import consulo.codeEditor.action.TypedActionHandler;
import consulo.codeEditor.action.TypedActionHandlerBase;
import consulo.dataContext.DataContext;
import consulo.document.FileDocumentManager;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

/**
 * Extends {@link TypedActionHandlerBase} to intercept keystrokes before the IDE's default brace/quote handling,
 * allowing LSP on-type formatting to process trigger characters (braces, commas, semicolons, etc.) first.
 * <p>
 * {@link consulo.language.editor.action.TypedHandlerDelegate TypedHandlerDelegate} cannot be used here because
 * its implementations run after brace/quote handling in {@code TypedHandler}.
 * <p>
 * The extension point for it ({@code ExtensionTypedActionHandler}) is internal, so {@link LspTypedActionHandlerInstaller} installs it once with the public
 * {@code TypedAction.setupHandler}, which wraps every registered handler - the same position as {@code order="last"}.
 */
public class LspTypedActionHandler extends TypedActionHandlerBase {

    public LspTypedActionHandler(@Nullable TypedActionHandler originalHandler) {
        init(originalHandler);
    }

    @Override
    @RequiredWriteAction
    public void execute(Editor editor, char charTyped, DataContext dataContext) {
        boolean shouldPerformOnTypeFormatting = LspOnTypeFormattingKt.shouldPerformOnTypeFormatting(editor);
        // make sure that all handlers finished sync work before calling performOnTypeFormatting()
        if (myOriginalHandler != null) {
            myOriginalHandler.execute(editor, charTyped, dataContext);
        }
        if (!shouldPerformOnTypeFormatting) {
            return;
        }

        VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (virtualFile == null) {
            return;
        }
        LspOnTypeFormattingKt.performOnTypeFormatting(editor, virtualFile, charTyped);
    }
}

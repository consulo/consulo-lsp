package com.intellij.platform.lsp.impl.features.selectWord;

import com.intellij.platform.lsp.api.customization.LspSelectionRangeCustomizer;
import com.intellij.platform.lsp.api.customization.LspSelectionRangeSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.localize.LspLocalize;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.Application;
import consulo.application.ReadAction;
import consulo.application.progress.ProgressManager;
import consulo.codeEditor.Editor;
import consulo.component.ProcessCanceledException;
import consulo.document.Document;
import consulo.document.util.TextRange;
import consulo.language.editor.action.ExtendWordSelectionHandler;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.project.Project;
import consulo.util.lang.function.ThrowableSupplier;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.SelectionRange;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@ExtensionImpl(id = "LspWordSelectionHandler")
public class LspWordSelectionHandler implements ExtendWordSelectionHandler {
    @Override
    public boolean canSelect(PsiElement e) {
        // See `com.intellij.codeInsight.editorActions.SelectWordUtil.processElement`
        // The platform collects all `ExtendWordSelectionHandlers` that return `true` from `canSelect()`
        // and then calls `select()` for all of them.
        // So, not to duplicate the first 6 lines of the `select()` implementation, it's safe to return `true` from here
        // and then return `null` from `select()` if there is no LSP-based info for this feature.
        return true;
    }

    /**
     * The platform calls it on the UI thread, see {@link #computeOffUiThread}.
     */
    @Override
    @RequiredReadAction
    public @Nullable List<TextRange> select(PsiElement e, CharSequence editorText, int cursorOffset, Editor editor) {
        PsiFile psiFile = e.getContainingFile();
        if (psiFile == null) {
            return null;
        }
        VirtualFile file = psiFile.getVirtualFile();
        if (file == null) {
            return null;
        }
        if (file instanceof VirtualFileWindow) {
            return null;
        }

        List<LspClientImpl> lspClients = new ArrayList<>();
        for (LspClientImpl client : LspClientManagerImpl.getInstanceImpl(psiFile.getProject()).getClientsWithThisFileOpen(file)) {
            if (shouldProceedWithServer(client, file)) {
                lspClients.add(client);
            }
        }
        if (lspClients.isEmpty()) {
            return null;
        }

        Document document = editor.getDocument();

        List<TextRange> result = new ArrayList<>();
        for (LspClientImpl lspClient : lspClients) {
            SelectionRange selectionRange = computeOffUiThread(
                psiFile.getProject(),
                () -> lspClient.getRequestExecutor().getSelectionRangeCaching(file, cursorOffset)
            );
            if (selectionRange != null) {
                result.addAll(extractTextRanges(document, selectionRange));
            }
        }
        return result;
    }

    /**
     * The platform calls {@code select()} on the UI thread ({@code SelectWordHandler}). On the UI thread the request runs in a background read action under a cancellable modal progress.
     */
    private static <T extends @Nullable Object> @Nullable T computeOffUiThread(Project project, Supplier<T> computation) {
        if (!Application.get().isDispatchThread()) {
            return computation.get();
        }
        try {
            return ProgressManager.getInstance().runProcessWithProgressSynchronously(
                (ThrowableSupplier<T, RuntimeException>) () -> ReadAction.compute(computation::get),
                LspLocalize.lspSelectionRangeProgressTitle(),
                true,
                project
            );
        }
        catch (ProcessCanceledException e) {
            return null;
        }
    }

    private boolean shouldProceedWithServer(LspClientImpl lspClient, VirtualFile file) {
        LspSelectionRangeCustomizer customizer = lspClient.getDescriptor().getLspCustomization().getSelectionRangeCustomizer();
        return customizer instanceof LspSelectionRangeSupport selectionRangeSupport &&
            LspServerCapabilitiesKt.supportsSelectionRange(lspClient, file) &&
            selectionRangeSupport.shouldAskServerForSelectionRange(file);
    }

    private List<TextRange> extractTextRanges(Document document, SelectionRange selectionRange) {
        List<TextRange> result = new ArrayList<>();
        SelectionRange current = selectionRange;
        while (current != null) {
            TextRange textRange = Lsp4jUtilKt.getRangeInDocument(document, current.getRange());
            if (textRange != null && (result.isEmpty() || !result.get(result.size() - 1).equals(textRange))) {
                result.add(textRange);
            }
            current = current.getParent();
        }
        return result;
    }
}

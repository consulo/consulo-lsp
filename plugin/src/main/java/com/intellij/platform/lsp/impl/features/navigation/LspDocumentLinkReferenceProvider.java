package com.intellij.platform.lsp.impl.features.navigation;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.features.highlighting.LspDocumentLink;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspCachedHighlighting;
import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.Application;
import consulo.application.ReadAction;
import consulo.application.progress.ProgressManager;
import consulo.codeEditor.Editor;
import consulo.component.ProcessCanceledException;
import consulo.document.util.TextRange;
import consulo.language.editor.inject.EditorWindow;
import consulo.language.editor.navigation.GotoDeclarationHandler;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.language.psi.PsiDocumentManager;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.project.Project;
import consulo.util.lang.function.ThrowableSupplier;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * This class is used to create references and therefore to follow links that were received from an LSP server using the
 * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_documentLink">textDocument/documentLink</a> request.
 * These links are rendered as links in the editor by {@link com.intellij.platform.lsp.impl.features.highlighting.LspHighlightingApplier}.
 * <p>
 * Consulo: IntelliJ's {@code ImplicitReferenceProvider} does not exist. Like {@link LspImplicitReferenceProvider}, this class is a
 * {@link GotoDeclarationHandler}: Go To Declaration and Ctrl+click/Ctrl+hover on a link navigate to its target.
 * The upstream {@code getImplicitReference(element, offsetInElement)} is kept as {@link #getImplicitReference(PsiFile, int)}.
 * A performed action calls the handler on the UI thread; resolving the link ({@code documentLink/resolve}) then runs in a
 * background thread under a modal progress.
 */
@ExtensionImpl(id = "LspDocumentLinkReferenceProvider", order = "after LspImplicitReferenceProvider")
public class LspDocumentLinkReferenceProvider implements GotoDeclarationHandler {
    @Override
    @RequiredReadAction
    public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement sourceElement, int offset, Editor editor) {
        if (editor instanceof EditorWindow) {
            return null;
        }
        Project project = editor.getProject();
        if (project == null || project.isDefault()) {
            return null;
        }
        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (psiFile == null) {
            return null;
        }

        LspDocumentLinkSymbolReference reference = getImplicitReference(psiFile, offset);
        if (reference == null) {
            return null;
        }
        boolean isCtrlHover = !Application.get().isDispatchThread();
        // Consulo's Ctrl+hover underlines the PSI leaf under the pointer, not the reference range.
        // In a file with flat PSI the leaf may span the whole file, so the underline is only shown when the leaf fits the range.
        if (isCtrlHover && (sourceElement == null || !reference.getRangeInElement().contains(sourceElement.getTextRange()))) {
            return null;
        }
        List<PsiElement> results = computeOffUiThread(project, reference::resolveReference);
        return results == null || results.isEmpty() ? null : results.toArray(PsiElement.EMPTY_ARRAY);
    }

    @RequiredReadAction
    public @Nullable LspDocumentLinkSymbolReference getImplicitReference(PsiFile psiFile, int offsetInElement) {
        if (psiFile.getProject().isDefault()) {
            return null;
        }
        VirtualFile file = psiFile.getVirtualFile();
        if (file == null) {
            return null;
        }
        if (file instanceof VirtualFileWindow) {
            return null;
        }

        for (LspClientImpl lspClient : LspClientManagerImpl.getInstanceImpl(psiFile.getProject()).getClientsWithThisFileOpen(file)) {
            List<LspCachedHighlighting<LspDocumentLink>> links = lspClient.getHighlightingCacheRegistry().getDocumentLinkCache().getHighlightings(file);
            for (LspCachedHighlighting<LspDocumentLink> documentLinkInfo : links) {
                if (documentLinkInfo.getTextRange().contains(offsetInElement)) {
                    return new LspDocumentLinkSymbolReference(
                        lspClient, psiFile, documentLinkInfo.getTextRange(), documentLinkInfo.getHighlightingInfo()
                    );
                }
            }
        }

        return null;
    }

    /**
     * Consulo: see the class comment.
     */
    private static <T extends @Nullable Object> @Nullable T computeOffUiThread(Project project, Supplier<T> computation) {
        if (!Application.get().isDispatchThread()) {
            return computation.get();
        }
        try {
            return ProgressManager.getInstance().runProcessWithProgressSynchronously(
                (ThrowableSupplier<T, RuntimeException>) () -> ReadAction.compute(computation::get),
                LspLocalize.lspResolvingReferenceProgressTitle(),
                true,
                project
            );
        }
        catch (ProcessCanceledException e) {
            return null;
        }
    }

    /**
     * Consulo: upstream {@code PsiSymbolReference}; Consulo has no symbol references, so this is a plain holder.
     */
    public static final class LspDocumentLinkSymbolReference {
        private final LspClientImpl myLspClient;
        private final PsiFile myPsiFile;
        private final TextRange myTextRange;
        private final LspDocumentLink myDocumentLink;

        private LspDocumentLinkSymbolReference(LspClientImpl lspClient, PsiFile psiFile, TextRange textRange, LspDocumentLink documentLink) {
            myLspClient = lspClient;
            myPsiFile = psiFile;
            myTextRange = textRange;
            myDocumentLink = documentLink;
        }

        public PsiElement getElement() {
            return myPsiFile;
        }

        public TextRange getRangeInElement() {
            return myTextRange;
        }

        public boolean resolvesTo(PsiElement target) {
            return false;
        }

        public List<PsiElement> resolveReference() {
            myDocumentLink.resolveDocumentLink(myLspClient);

            String uri = myDocumentLink.getTargetUri();
            if (uri == null) {
                return List.of();
            }
            if (uri.startsWith("http://") || uri.startsWith("https://")) {
                return List.of(new LspOpenBrowserNavigatableSymbol(myLspClient.getProject(), uri, myDocumentLink.getTooltip()));
            }

            VirtualFile targetFileOrDir = myLspClient.getDescriptor().findFileByUri(uri);
            if (targetFileOrDir == null) {
                return List.of();
            }
            return List.of(new LspPathDocumentLinkSymbol(
                myLspClient.getProject(), targetFileOrDir, Arrays.asList(myLspClient.getDescriptor().getRoots())
            ));
        }
    }
}

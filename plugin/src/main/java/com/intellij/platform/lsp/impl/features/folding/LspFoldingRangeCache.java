package com.intellij.platform.lsp.impl.features.folding;

import com.intellij.platform.lsp.api.customization.LspFoldingRangeSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspDocumentMapping;
import com.intellij.platform.lsp.impl.LspResponseMappingKt;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspHighlightingCache;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspPullResult;
import consulo.application.Application;
import consulo.codeEditor.Editor;
import consulo.codeEditor.EditorFactory;
import consulo.component.util.ModificationTracker;
import consulo.component.util.SimpleModificationTracker;
import consulo.language.editor.DaemonCodeAnalyzer;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.project.Project;
import consulo.util.lang.Pair;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_foldingRange">textDocument/foldingRange</a>
 * <p>
 * Folding is updated by the daemon's
 * folding pass, which caches its result per editor (dependencies: the PSI file, the folding model and the descriptors'
 * dependencies). So {@link #onResponseReceived} increments {@link #getModificationTracker()} (a dependency of every
 * {@link LspFoldingBuilder} descriptor) and restarts the daemon for the file.
 */
public class LspFoldingRangeCache extends LspHighlightingCache<FoldingRange> {
    private final LspClientImpl myLspClient;
    private final SimpleModificationTracker myModificationTracker = new SimpleModificationTracker();

    public LspFoldingRangeCache(LspClientImpl lspClient) {
        super(lspClient.getProject());
        myLspClient = lspClient;
    }

    /**
     * Consulo-only: incremented each time a server response is applied to the cache.
     */
    public ModificationTracker getModificationTracker() {
        return myModificationTracker;
    }

    @Override
    public boolean isSupportedForFile(VirtualFile file) {
        return myLspClient.getDescriptor().getLspCustomization().getFoldingRangeCustomizer() instanceof LspFoldingRangeSupport
            && LspServerCapabilitiesKt.supportsFoldingRange(myLspClient, file);
    }

    @Override
    protected CompletableFuture<LspPullResult<FoldingRange>> sendRequest(VirtualFile file) {
        CompletableFuture<List<@Nullable List<Pair<Range, FoldingRange>>>> perDocument =
            myLspClient.getDocumentMapping().forEachDocumentInFile(file, lspDocument -> {
                FoldingRangeRequestParams params = new FoldingRangeRequestParams(lspDocument.getId());
                return myLspClient.sendRequest(server -> server.getTextDocumentService().foldingRange(params))
                    .thenApply(foldingRanges -> {
                        if (foldingRanges == null) {
                            return null;
                        }
                        List<Pair<Range, FoldingRange>> result = new ArrayList<>(foldingRanges.size());
                        for (FoldingRange foldingRange : foldingRanges) {
                            FoldingRange mapped = LspResponseMappingKt.mapFoldingRange(lspDocument, foldingRange);
                            result.add(Pair.create(foldingRangeToLsp4jRange(mapped), mapped));
                        }
                        return result;
                    });
            });
        return perDocument.thenApply(LspDocumentMapping::aggregateToPullResult);
    }

    @Override
    protected CompletableFuture<@Nullable Void> onResponseReceived(VirtualFile file) {
        Project project = myLspClient.getProject();
        myModificationTracker.incModificationCount();
        // the daemon (and so its folding pass) is restarted for the file if an editor shows it
        Application.get().invokeLater(() -> {
            if (project.isDisposed() || !file.isValid()) {
                return;
            }
            boolean hasEditor = false;
            for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
                if (editor.getProject() == project && Objects.equals(editor.getVirtualFile(), file)) {
                    hasEditor = true;
                    break;
                }
            }
            if (!hasEditor) {
                return;
            }
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (psiFile != null) {
                DaemonCodeAnalyzer.getInstance(project).restart(psiFile);
            }
        }, project.getDisposed());
        return CompletableFuture.completedFuture(null);
    }

    private static Range foldingRangeToLsp4jRange(FoldingRange foldingRange) {
        int startCharacter = foldingRange.getStartCharacter() != null ? foldingRange.getStartCharacter() : Integer.MAX_VALUE;
        int endCharacter = foldingRange.getEndCharacter() != null ? foldingRange.getEndCharacter() : Integer.MAX_VALUE;

        Position startPosition = new Position(foldingRange.getStartLine(), startCharacter);
        Position endPosition = new Position(foldingRange.getEndLine(), endCharacter);

        return new Range(startPosition, endPosition);
    }
}

package com.intellij.platform.lsp.impl.features.inlayHint;

import com.intellij.platform.lsp.api.customization.LspInlayHintCustomizer;
import com.intellij.platform.lsp.api.customization.LspInlayHintSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspDocumentMapping;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspHighlightingCache;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspPullResult;
import com.intellij.platform.lsp.impl.features.inlayCommon.LspInlayApplier;
import consulo.util.lang.Pair;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_inlayHint">textDocument/inlayHint</a>
 */
public class LspInlayHintsCache extends LspHighlightingCache<InlayHint> {
    private final LspClientImpl myLspClient;

    public LspInlayHintsCache(LspClientImpl lspClient) {
        super(lspClient.getProject());
        myLspClient = lspClient;
    }

    @Override
    public boolean isSupportedForFile(VirtualFile file) {
        LspInlayHintCustomizer customizer = myLspClient.getDescriptor().getLspCustomization().getInlayHintCustomizer();
        return customizer instanceof LspInlayHintSupport support
            && LspServerCapabilitiesKt.supportsInlayHints(myLspClient, file)
            && support.shouldAskServerForInlayHints(file);
    }

    @Override
    protected CompletableFuture<LspPullResult<InlayHint>> sendRequest(VirtualFile file) {
        CompletableFuture<List<@Nullable List<Pair<Range, InlayHint>>>> perDocument =
            myLspClient.getDocumentMapping().forEachDocumentInFile(file, (lspDocument, range) -> {
                InlayHintParams params = new InlayHintParams(lspDocument.getId(), range);
                return myLspClient.sendRequest(server -> server.getTextDocumentService().inlayHint(params))
                    .<@Nullable List<Pair<Range, InlayHint>>>thenApply(hints -> {
                        if (hints == null) {
                            return null;
                        }
                        List<Pair<Range, InlayHint>> result = new ArrayList<>(hints.size());
                        for (InlayHint hint : hints) {
                            Position mappedPosition = lspDocument.toHostPosition(hint.getPosition());
                            result.add(Pair.create(new Range(mappedPosition, mappedPosition), hint));
                        }
                        return result;
                    });
            });
        return perDocument.thenApply(LspDocumentMapping::aggregateToPullResult);
    }

    @Override
    protected CompletableFuture<@Nullable Void> onResponseReceived(VirtualFile file) {
        // Re-apply the inlays of the file instead of waiting for the next daemon pass.
        LspInlayApplier.getInstance(myLspClient.getProject()).scheduleRefresh(file);
        return CompletableFuture.completedFuture(null);
    }
}

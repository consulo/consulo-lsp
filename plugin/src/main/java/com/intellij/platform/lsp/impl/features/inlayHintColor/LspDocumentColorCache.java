package com.intellij.platform.lsp.impl.features.inlayHintColor;

import com.intellij.platform.lsp.api.customization.LspDocumentColorSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspDocumentMapping;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspHighlightingCache;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspPullResult;
import com.intellij.platform.lsp.impl.features.inlayCommon.LspInlayApplier;
import consulo.util.lang.Pair;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Color;
import org.eclipse.lsp4j.ColorInformation;
import org.eclipse.lsp4j.DocumentColorParams;
import org.eclipse.lsp4j.Range;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_documentColor">textDocument/documentColor</a>
 */
public class LspDocumentColorCache extends LspHighlightingCache<Color> {
    private final LspClientImpl myLspClient;

    public LspDocumentColorCache(LspClientImpl lspClient) {
        super(lspClient.getProject());
        myLspClient = lspClient;
    }

    @Override
    public boolean isSupportedForFile(VirtualFile file) {
        return myLspClient.getDescriptor().getLspCustomization().getDocumentColorCustomizer() instanceof LspDocumentColorSupport
            && LspServerCapabilitiesKt.supportsDocumentColor(myLspClient, file);
    }

    @Override
    protected CompletableFuture<LspPullResult<Color>> sendRequest(VirtualFile file) {
        CompletableFuture<List<@Nullable List<Pair<Range, Color>>>> perDocument =
            myLspClient.getDocumentMapping().forEachDocumentInFile(file, lspDocument -> {
                DocumentColorParams params = new DocumentColorParams(lspDocument.getId());
                return myLspClient.sendRequest(server -> server.getTextDocumentService().documentColor(params))
                    .<@Nullable List<Pair<Range, Color>>>thenApply(colors -> {
                        if (colors == null) {
                            return null;
                        }
                        List<Pair<Range, Color>> result = new ArrayList<>(colors.size());
                        for (ColorInformation info : colors) {
                            result.add(Pair.create(lspDocument.toHostRange(info.getRange()), info.getColor()));
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

package com.intellij.platform.lsp.impl.features.highlighting;

import com.intellij.platform.lsp.api.customization.LspDocumentLinkSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspDocumentMapping;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspHighlightingCache;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspPullResult;
import consulo.util.lang.Pair;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.DocumentLink;
import org.eclipse.lsp4j.DocumentLinkParams;
import org.eclipse.lsp4j.Range;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_documentLink">textDocument/documentLink</a>
 */
public class LspDocumentLinkCache extends LspHighlightingCache<LspDocumentLink> {
    private final LspClientImpl myLspClient;

    public LspDocumentLinkCache(LspClientImpl lspClient) {
        super(lspClient.getProject());
        myLspClient = lspClient;
    }

    @Override
    public boolean isSupportedForFile(VirtualFile file) {
        return myLspClient.getDescriptor().getLspCustomization().getDocumentLinkCustomizer() instanceof LspDocumentLinkSupport
            && LspServerCapabilitiesKt.supportsDocumentLink(myLspClient, file);
    }

    @Override
    protected CompletableFuture<LspPullResult<LspDocumentLink>> sendRequest(VirtualFile file) {
        CompletableFuture<List<@Nullable List<Pair<Range, LspDocumentLink>>>> perDocument =
            myLspClient.getDocumentMapping().forEachDocumentInFile(file, lspDocument -> {
                DocumentLinkParams params = new DocumentLinkParams(lspDocument.getId());
                return myLspClient.sendRequest(server -> server.getTextDocumentService().documentLink(params))
                    .thenApply(links -> {
                        if (links == null) {
                            return null;
                        }
                        List<Pair<Range, LspDocumentLink>> result = new ArrayList<>(links.size());
                        for (DocumentLink link : links) {
                            result.add(Pair.create(lspDocument.toHostRange(link.getRange()), new LspDocumentLink(link)));
                        }
                        return result;
                    });
            });
        return perDocument.thenApply(LspDocumentMapping::aggregateToPullResult);
    }

    @Override
    protected CompletableFuture<@Nullable Void> onResponseReceived(VirtualFile file) {
        LspHighlightingApplier.getInstance(myLspClient.getProject()).scheduleHighlightingRefresh(file);
        myLspClient.notifyDocumentLinksReceived(file);
        return CompletableFuture.completedFuture(null);
    }
}

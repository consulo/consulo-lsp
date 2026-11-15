package com.intellij.platform.lsp.impl.features.codeLens;

import com.intellij.platform.lsp.api.customization.LspCodeLensCustomizer;
import com.intellij.platform.lsp.api.customization.LspCodeLensSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspDocument;
import com.intellij.platform.lsp.impl.LspDocumentMapping;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.impl.features.LspFeaturesRefreshing;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspHighlightingCache;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspPullResult;
import consulo.util.lang.Pair;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class LspCodeLensCache extends LspHighlightingCache<CodeLens> {
    private final LspClientImpl myLspClient;

    public LspCodeLensCache(LspClientImpl lspClient) {
        super(lspClient.getProject());
        myLspClient = lspClient;
    }

    @Override
    public boolean isSupportedForFile(VirtualFile file) {
        LspCodeLensCustomizer customizer = myLspClient.getDescriptor().getLspCustomization().getCodeLensCustomizer();
        return customizer instanceof LspCodeLensSupport codeLensSupport &&
            LspServerCapabilitiesKt.supportsCodeLens(myLspClient, file) &&
            codeLensSupport.shouldAskServerForCodeLenses(file);
    }

    @Override
    protected CompletableFuture<LspPullResult<CodeLens>> sendRequest(VirtualFile file) {
        ServerCapabilities serverCapabilities = myLspClient.getServerCapabilities();
        CodeLensOptions codeLensProvider = serverCapabilities != null ? serverCapabilities.getCodeLensProvider() : null;
        boolean resolveSupported = codeLensProvider != null && Boolean.TRUE.equals(codeLensProvider.getResolveProvider());
        CompletableFuture<List<@Nullable List<Pair<Range, CodeLens>>>> perDocument =
            myLspClient.getDocumentMapping().forEachDocumentInFile(file, lspDocument -> {
                CodeLensParams params = new CodeLensParams(lspDocument.getId());
                return myLspClient.sendRequest(server -> server.getTextDocumentService().codeLens(params))
                    .thenCompose(result -> {
                        if (result == null) {
                            return CompletableFuture.completedFuture(null);
                        }

                        CompletableFuture<List<CodeLens>> lenses;
                        if (resolveSupported) {
                            lenses = resolveLenses(result);
                        }
                        else {
                            lenses = CompletableFuture.completedFuture(new ArrayList<>(result));
                        }

                        return lenses.thenApply(it -> toHostRanges(lspDocument, it));
                    });
            });
        return perDocument.thenApply(LspDocumentMapping::aggregateToPullResult);
    }

    /**
     * Resolves the lenses without a command one by one, in order (upstream: sequential suspending calls in {@code map}).
     */
    private CompletableFuture<List<CodeLens>> resolveLenses(List<? extends CodeLens> result) {
        CompletableFuture<List<CodeLens>> chain = CompletableFuture.completedFuture(new ArrayList<>(result.size()));
        for (CodeLens lens : result) {
            chain = chain.thenCompose(resolved -> {
                if (lens.getCommand() != null) {
                    resolved.add(lens);
                    return CompletableFuture.completedFuture(resolved);
                }
                return myLspClient.sendRequest(server -> server.getTextDocumentService().resolveCodeLens(lens))
                    .thenApply(resolvedLens -> {
                        resolved.add(resolvedLens != null ? resolvedLens : lens);
                        return resolved;
                    });
            });
        }
        return chain;
    }

    private static List<Pair<Range, CodeLens>> toHostRanges(LspDocument lspDocument, List<CodeLens> lenses) {
        List<Pair<Range, CodeLens>> result = new ArrayList<>(lenses.size());
        for (CodeLens lens : lenses) {
            result.add(Pair.create(lspDocument.toHostRange(lens.getRange()), lens));
        }
        return result;
    }

    @Override
    protected CompletableFuture<@Nullable Void> onResponseReceived(VirtualFile file) {
        LspFeaturesRefreshing.refreshCodeLenses(myLspClient.getProject());
        return CompletableFuture.completedFuture(null);
    }
}

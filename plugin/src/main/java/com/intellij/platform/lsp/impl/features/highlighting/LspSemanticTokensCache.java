package com.intellij.platform.lsp.impl.features.highlighting;

import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspDocumentMapping;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspHighlightingCache;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspPullResult;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.application.Application;
import consulo.application.progress.ProgressManager;
import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.codeEditor.EditorFactory;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.logging.Logger;
import consulo.util.lang.Pair;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * <a href="https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_semanticTokens">Semantic Tokens</a>
 */
public class LspSemanticTokensCache extends LspHighlightingCache<LspSemanticToken> {
    private static final Logger LOG = Logger.getInstance(LspSemanticTokensCache.class);

    private final LspClientImpl myLspClient;

    public LspSemanticTokensCache(LspClientImpl lspClient) {
        super(lspClient.getProject());
        myLspClient = lspClient;
    }

    @Override
    @RequiredReadAction
    public boolean isSupportedForFile(VirtualFile file) {
        if (!(myLspClient.getDescriptor().getLspCustomization().getSemanticTokensCustomizer()
            instanceof LspSemanticTokensSupport semanticTokensSupport)) {
            return false;
        }
        PsiFile psiFile = PsiManager.getInstance(myLspClient.getProject()).findFile(file);
        if (psiFile == null) {
            return false;
        }
        if (!semanticTokensSupport.shouldAskServerForSemanticTokens(psiFile)) {
            return false;
        }

        SemanticTokensWithRegistrationOptions provider = getSemanticTokensProvider();
        if (provider == null || provider.getFull() == null) {
            return false;
        }
        Boolean left = provider.getFull().getLeft();
        return left == null || left;
    }

    private @Nullable SemanticTokensWithRegistrationOptions getSemanticTokensProvider() {
        ServerCapabilities serverCapabilities = myLspClient.getServerCapabilities();
        return serverCapabilities != null ? serverCapabilities.getSemanticTokensProvider() : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected CompletableFuture<LspPullResult<LspSemanticToken>> sendRequest(VirtualFile file) {
        SemanticTokensWithRegistrationOptions provider = getSemanticTokensProvider();
        SemanticTokensLegend legend = provider != null ? provider.getLegend() : null;
        if (legend == null) {
            return CompletableFuture.completedFuture(LspPullResult.Failed.INSTANCE);
        }
        Long initialDocModStamp = readAction(() -> {
            Document document = FileDocumentManager.getInstance().getDocument(file);
            return document != null ? document.getModificationStamp() : null;
        });
        if (initialDocModStamp == null) {
            return CompletableFuture.completedFuture(LspPullResult.Failed.INSTANCE);
        }

        CompletableFuture<List<@Nullable List<Pair<Range, LspSemanticToken>>>> perDocument =
            myLspClient.getDocumentMapping().forEachDocumentInFile(file, (lspDocument, cellRange) ->
                myLspClient.sendRequest(server -> server.getTextDocumentService().semanticTokensFull(new SemanticTokensParams(lspDocument.getId())))
                    .<@Nullable List<Pair<Range, LspSemanticToken>>>thenApplyAsync(response -> {
                        if (response == null) {
                            return null;
                        }
                        return readAction(() -> {
                            Document hostDocument = FileDocumentManager.getInstance().getDocument(file);
                            if (hostDocument == null) {
                                return null;
                            }

                            if (hostDocument.getModificationStamp() != initialDocModStamp) {
                                // The result will be ignored anyway in `LspHighlightingCache.responseReceived`, so let's save time by not calling decodeSemanticTokens()
                                return List.of();
                            }
                            Document document = lspDocument.prepareDocument(hostDocument, host -> {
                                Integer cellStartOffset = Lsp4jUtilKt.getOffsetInDocument(host, cellRange.getStart());
                                if (cellStartOffset == null) {
                                    return null;
                                }
                                Integer cellEndOffset = Lsp4jUtilKt.getOffsetInDocument(host, cellRange.getEnd());
                                if (cellEndOffset == null) {
                                    return null;
                                }
                                CharSequence cellText = host.getCharsSequence().subSequence(cellStartOffset, cellEndOffset);
                                return EditorFactory.getInstance().createDocument(cellText);
                            });
                            if (document == null) {
                                return null;
                            }

                            List<Pair<Range, LspSemanticToken>> decoded = decodeSemanticTokens(document, response.getData(), legend);
                            List<Pair<Range, LspSemanticToken>> result = new ArrayList<>(decoded.size());
                            for (Pair<Range, LspSemanticToken> pair : decoded) {
                                result.add(Pair.create(lspDocument.toHostRange(pair.getFirst()), pair.getSecond()));
                            }
                            return result;
                        });
                    }, AppExecutorUtil.getAppExecutorService())
            );
        return perDocument.thenApply(LspDocumentMapping::aggregateToPullResult);
    }

    @RequiredReadAction
    private static List<Pair<Range, LspSemanticToken>> decodeSemanticTokens(
        Document document,
        List<Integer> data,
        SemanticTokensLegend legend
    ) {
        if (data.size() % 5 != 0) {
            LOG.warn("Unexpected semantic tokens data length from the server: " + data.size());
            return List.of();
        }

        // https://microsoft.github.io/language-server-protocol/specification#textDocument_semanticTokens
        // at index 5*i - deltaLine: token line number, relative to the previous token
        // at index 5*i+1 - deltaStart: token start character, relative to the previous token (relative to 0 or the previous token’s start if they are on the same line)
        // at index 5*i+2 - length: the length of the token.
        // at index 5*i+3 - tokenType: will be looked up in SemanticTokensLegend.tokenTypes. We currently ask that tokenType < 65536.
        // at index 5*i+4 - tokenModifiers: each set bit will be looked up in SemanticTokensLegend.tokenModifiers

        int line = 0;
        int column = 0;
        int numTokens = data.size() / 5;

        List<Pair<Range, LspSemanticToken>> result = new ArrayList<>(numTokens);

        for (int i = 0; i < numTokens; i++) {
            if (i % 100 == 0) {
                ProgressManager.checkCanceled();
            }

            int deltaLine = data.get(5 * i);
            int deltaStart = data.get(5 * i + 1);
            int length = data.get(5 * i + 2);
            int encodedTokenType = data.get(5 * i + 3);
            int encodedTokenModifiers = data.get(5 * i + 4);

            line += deltaLine;
            column = deltaLine == 0 ? column + deltaStart : deltaStart;

            if (encodedTokenType >= legend.getTokenTypes().size()) {
                LOG.warn("Unexpected encodedTokenType: " + encodedTokenType + ", legend.tokenTypes.size = " + legend.getTokenTypes().size());
                continue;
            }

            String tokenType = legend.getTokenTypes().get(encodedTokenType);

            List<String> tokenModifiers = new ArrayList<>();
            List<String> legendModifiers = legend.getTokenModifiers();
            for (int index = 0; index < legendModifiers.size(); index++) {
                if ((encodedTokenModifiers & (1 << index)) != 0) {
                    tokenModifiers.add(legendModifiers.get(index));
                }
            }

            Position startPosition = new Position(line, column);
            Integer offset = Lsp4jUtilKt.getOffsetInDocument(document, startPosition);
            if (offset == null) {
                continue;
            }
            int endOffset = offset + length;
            if (endOffset > document.getTextLength()) {
                continue;
            }
            Position endPosition = Lsp4jUtilKt.getLsp4jPosition(document, endOffset);

            result.add(Pair.create(new Range(startPosition, endPosition), new LspSemanticToken(tokenType, tokenModifiers)));
        }

        return result;
    }

    @Override
    protected CompletableFuture<@Nullable Void> onResponseReceived(VirtualFile file) {
        LspHighlightingApplier.getInstance(myLspClient.getProject()).scheduleHighlightingRefresh(file);
        return CompletableFuture.completedFuture(null);
    }

    private static <R> @Nullable R readAction(Supplier<@Nullable R> computation) {
        return Application.get().runReadAction(computation);
    }
}

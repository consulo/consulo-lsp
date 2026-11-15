package com.intellij.platform.lsp.impl;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.impl.cache.LspCache;
import com.intellij.platform.lsp.impl.cache.LspPerFileCache;
import com.intellij.platform.lsp.impl.cache.LspSingleSlotCache;
import com.intellij.platform.lsp.impl.features.completion.LspCompletionUtilKt;
import com.intellij.platform.lsp.impl.features.documentSymbol.ConvertersKt;
import com.intellij.platform.lsp.impl.features.documentation.HoverResultCache;
import com.intellij.platform.lsp.impl.features.documentation.TextRangeAndMarkupContent;
import com.intellij.platform.lsp.impl.features.highlighting.LspDocumentHighlightCache;
import com.intellij.platform.lsp.impl.features.highlighting.TextRangeAndHighlightKind;
import com.intellij.platform.lsp.impl.features.navigation.LspDefinitionCache;
import com.intellij.platform.lsp.impl.features.navigation.TextRangeAndLocationLinks;
import com.intellij.platform.lsp.impl.util.Lsp4jConvertersKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.SelectionRange;
import org.eclipse.lsp4j.SelectionRangeParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TypeDefinitionParams;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class LspRequestExecutor extends LspRequestExecutorBase {
    private final LspClientImpl myLspClient;
    private final LspDocumentMapping myDocumentMapping;

    private final List<LspCache> myAllCaches = new ArrayList<>();

    private final HoverResultCache myHoverResultCache;
    private final LspDefinitionCache myDefinitionCache;
    private final LspDefinitionCache myTypeDefinitionCache;
    private final LspSingleSlotCache<String, List<WorkspaceSymbol>> myWorkspaceSymbolCache;
    // documentSymbol and selectionRange results depend only on the requested document,
    // so a change in another file must not evict them.
    private final LspPerFileCache<Object, List<DocumentSymbol>> myDocumentSymbolCache;
    private final LspDocumentHighlightCache myDocumentHighlightCache;
    private final LspPerFileCache<Integer, SelectionRange> mySelectionRangeCache;

    public LspRequestExecutor(LspClientImpl lspClient, LspDocumentMapping documentMapping) {
        super(lspClient);
        myLspClient = lspClient;
        myDocumentMapping = documentMapping;

        myHoverResultCache = register(new HoverResultCache(lspClient.getProject()));
        myDefinitionCache = register(new LspDefinitionCache(lspClient.getProject()));
        myTypeDefinitionCache = register(new LspDefinitionCache(lspClient.getProject()));
        myWorkspaceSymbolCache = register(new LspSingleSlotCache<String, List<WorkspaceSymbol>>(lspClient.getProject()));
        myDocumentSymbolCache = register(new LspPerFileCache<Object, List<DocumentSymbol>>(lspClient.getProject(), true));
        myDocumentHighlightCache = register(new LspDocumentHighlightCache(lspClient.getProject()));
        mySelectionRangeCache = register(new LspPerFileCache<Integer, SelectionRange>(lspClient.getProject(), true));
    }

    private <T extends LspCache> T register(T cache) {
        myAllCaches.add(cache);
        return cache;
    }

    @Override
    public void afterShutdown() {
        clearCaches();
    }

    /**
     * Drops every cached response, so the next reader asks the server again.
     */
    public void clearCaches() {
        myAllCaches.forEach(LspCache::clearCache);
    }

    /**
     * Must be called on a background thread.
     */
    @RequiredReadAction
    public @Nullable CompletionList getCompletionList(VirtualFile file, int offset, boolean isAutoPopup) {
        return awaitSync(getCompletionListAsync(file, offset, isAutoPopup));
    }

    /**
     * Must be called on a background thread.
     */
    @RequiredReadAction
    public CompletableFuture<@Nullable CompletionList> getCompletionListAsync(VirtualFile file, int offset, boolean isAutoPopup) {
        LspDocumentMapping.HostCoordinates host = myDocumentMapping.unwrapInjection(file, offset);
        if (host == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletionContext completionContext =
            LspCompletionUtilKt.createCompletionContext(myLspClient, host.getHostDocument(), host.getHostOffset(), isAutoPopup);
        CompletableFuture<@Nullable CompletionList> future =
            myDocumentMapping.withDocumentAtOffset(host.getHostFile(), host.getHostDocument(), host.getHostOffset(), (lspDocument, position) -> {
                CompletionParams params = new CompletionParams(lspDocument.getId(), position, completionContext);
                CompletableFuture<@Nullable Either<List<CompletionItem>, CompletionList>> source =
                    doSendRequestAsync(it -> it.getTextDocumentService().completion(params));
                if (source == null) {
                    return CompletableFuture.completedFuture(null);
                }

                CompletableFuture<@Nullable CompletionList> result = new CompletableFuture<>();
                source.whenComplete((lsp4jResponse, throwable) -> {
                    if (throwable != null) {
                        result.completeExceptionally(throwable);
                    }
                    else {
                        try {
                            result.complete(lsp4jResponse != null ? LspCompletionUtilKt.toCompletionList(lsp4jResponse) : null);
                        }
                        catch (Throwable t) {
                            result.completeExceptionally(t);
                        }
                    }
                });
                // CompletableFuture cancellation does not propagate upstream through derived stages.
                // Wire it explicitly: a cancellation of `result` (the caller typed on, or the coroutine got cancelled)
                // cancels `source`, and doSendRequestAsync then sends $/cancelRequest to the server.
                result.whenComplete((r, t) -> {
                    if (result.isCancelled()) {
                        source.cancel(true);
                    }
                });
                return result;
            });
        return future != null ? future : CompletableFuture.completedFuture(null);
    }

    /**
     * Must be called on a background thread.
     */
    @RequiredReadAction
    public List<LocationLink> getElementDefinitions(VirtualFile file, int offset) {
        TextRangeAndLocationLinks result = myDefinitionCache.getOrCompute(file, offset, () ->
            myDocumentMapping.withDocumentAtFileOffset(file, offset, (lspDocument, position) -> {
                DefinitionParams params = new DefinitionParams(lspDocument.getId(), position);
                Either<List<? extends Location>, List<? extends LocationLink>> lsp4jResponse =
                    sendRequestSync(it -> it.getTextDocumentService().definition(params));
                if (lsp4jResponse == null) {
                    return null;
                }
                return toHostLocationLinks(toLocationLinks(lsp4jResponse), file, offset);
            })
        );
        return result != null ? result.getLocationLinks() : List.of();
    }

    /**
     * Must be called on a background thread.
     */
    @RequiredReadAction
    public @Nullable SignatureHelp getSignatureHelp(VirtualFile file, int offset) {
        return myDocumentMapping.withDocumentAtFileOffset(file, offset, (lspDocument, position) -> {
            SignatureHelpParams params = new SignatureHelpParams(lspDocument.getId(), position);
            return sendRequestSync(it -> it.getTextDocumentService().signatureHelp(params));
        });
    }

    /**
     * Must be called on a background thread.
     */
    @RequiredReadAction
    public List<LocationLink> getTypeDefinitions(VirtualFile file, int offset) {
        TextRangeAndLocationLinks result = myTypeDefinitionCache.getOrCompute(file, offset, () ->
            myDocumentMapping.withDocumentAtFileOffset(file, offset, (lspDocument, position) -> {
                TypeDefinitionParams params = new TypeDefinitionParams(lspDocument.getId(), position);
                Either<List<? extends Location>, List<? extends LocationLink>> lsp4jResponse =
                    sendRequestSync(it -> it.getTextDocumentService().typeDefinition(params));
                if (lsp4jResponse == null) {
                    return null;
                }
                return toHostLocationLinks(toLocationLinks(lsp4jResponse), file, offset);
            })
        );
        return result != null ? result.getLocationLinks() : List.of();
    }

    private static List<LocationLink> toLocationLinks(Either<List<? extends Location>, List<? extends LocationLink>> lsp4jResponse) {
        return lsp4jResponse.map(
            items -> {
                List<LocationLink> links = new ArrayList<>();
                for (Location item : items) {
                    links.add(Lsp4jConvertersKt.toLocationLink(item));
                }
                return links;
            },
            links -> new ArrayList<LocationLink>(links)
        );
    }

    /**
     * Maps the targets of {@code locationLinks} to host-file coordinates and pairs them with the origin range reported by the server,
     * which is what {@link LspDefinitionCache} matches subsequent lookups against.
     */
    private TextRangeAndLocationLinks toHostLocationLinks(List<LocationLink> locationLinks, VirtualFile file, int offset) {
        List<LocationLink> hostLocationLinks = toHostLocationLinks(locationLinks);
        Document document = FileDocumentManager.getInstance().getDocument(file);
        return TextRangeAndLocationLinks.fromLocationLinks(hostLocationLinks, document, offset);
    }

    private List<LocationLink> toHostLocationLinks(List<LocationLink> locationLinks) {
        LinkedHashSet<LocationLink> result = new LinkedHashSet<>();
        for (LocationLink link : locationLinks) {
            LspDocument lspDocument = myDocumentMapping.findDocumentByUrl(link.getTargetUri());
            result.add(lspDocument != null ? LspResponseMappingKt.mapLocationLink(lspDocument, link) : link);
        }
        return new ArrayList<>(result);
    }

    /**
     * Must be called on a background thread.
     */
    @RequiredReadAction
    public List<LocationLink> getImplementations(VirtualFile file, int offset) {
        List<LocationLink> result = myDocumentMapping.withDocumentAtFileOffset(file, offset, (lspDocument, position) -> {
            ImplementationParams params = new ImplementationParams(lspDocument.getId(), position);
            Either<List<? extends Location>, List<? extends LocationLink>> lsp4jResponse =
                sendRequestSync(it -> it.getTextDocumentService().implementation(params));
            if (lsp4jResponse == null) {
                return List.of();
            }
            return toHostLocationLinks(toLocationLinks(lsp4jResponse));
        });
        return result != null ? result : List.of();
    }

    /**
     * Must be called on a background thread.
     */
    public @Nullable List<WorkspaceSymbol> getWorkspaceSymbolsCaching(String query) {
        return getWorkspaceSymbolsCaching(query, LspClient.DEFAULT_REQUEST_TIMEOUT_MS);
    }

    /**
     * Must be called on a background thread.
     */
    public @Nullable List<WorkspaceSymbol> getWorkspaceSymbolsCaching(String query, int timeoutMs) {
        return myWorkspaceSymbolCache.getOrCompute(query, () -> {
            WorkspaceSymbolParams params = new WorkspaceSymbolParams(query);
            Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> response =
                sendRequestSync(timeoutMs, it -> it.getWorkspaceService().symbol(params));
            if (response == null) {
                return null;
            }
            return response.map(
                items -> {
                    List<WorkspaceSymbol> symbols = new ArrayList<>();
                    for (SymbolInformation item : items) {
                        symbols.add(com.intellij.platform.lsp.impl.features.workspaceSymbol.ConvertersKt.toWorkspaceSymbol(item));
                    }
                    return symbols;
                },
                symbols -> new ArrayList<WorkspaceSymbol>(symbols)
            );
        });
    }

    /**
     * Must be called on a background thread.
     */
    public @Nullable List<DocumentSymbol> getDocumentSymbolsCaching(VirtualFile file) {
        return LspPerFileCache.getOrCompute(myDocumentSymbolCache, file, () -> {
            List<LspDocument> lspDocuments = myDocumentMapping.getDocumentsInFileSync(file);
            List<@Nullable List<DocumentSymbol>> perDocument = new ArrayList<>();
            for (LspDocument lspDocument : lspDocuments) {
                DocumentSymbolParams params = new DocumentSymbolParams(lspDocument.getId());
                List<Either<SymbolInformation, DocumentSymbol>> results =
                    sendRequestSync(it -> it.getTextDocumentService().documentSymbol(params));
                if (results == null) {
                    perDocument.add(null);
                    continue;
                }
                List<DocumentSymbol> mapped = new ArrayList<>();
                for (DocumentSymbol symbol : ConvertersKt.toDocumentSymbols(results)) {
                    mapped.add(LspResponseMappingKt.mapDocumentSymbol(lspDocument, symbol));
                }
                perDocument.add(mapped);
            }
            return LspDocumentMapping.aggregatePerDocumentResults(perDocument);
        });
    }

    /**
     * Must be called on a background thread.
     */
    @RequiredReadAction
    public @Nullable List<TextRangeAndHighlightKind> getDocumentHighlightsCaching(VirtualFile file, int offset) {
        return myDocumentHighlightCache.getOrCompute(file, offset, () ->
            myDocumentMapping.withDocumentAtFileOffset(file, offset, (lspDocument, position) -> {
                DocumentHighlightParams params = new DocumentHighlightParams(lspDocument.getId(), position);
                List<? extends DocumentHighlight> highlights = sendRequestSync(it -> it.getTextDocumentService().documentHighlight(params));
                if (highlights == null) {
                    return null;
                }
                Document document = FileDocumentManager.getInstance().getDocument(file);
                if (document == null) {
                    return null;
                }
                List<DocumentHighlight> hostMappedHighlights = new ArrayList<>();
                for (DocumentHighlight it : highlights) {
                    hostMappedHighlights.add(new DocumentHighlight(lspDocument.toHostRange(it.getRange()), it.getKind()));
                }
                return TextRangeAndHighlightKind.fromDocumentHighlights(hostMappedHighlights, document);
            })
        );
    }

    /**
     * Must be called on a background thread.
     */
    @RequiredReadAction
    public @Nullable SelectionRange getSelectionRangeCaching(VirtualFile file, int offset) {
        return mySelectionRangeCache.getOrCompute(file, offset, () ->
            myDocumentMapping.withDocumentAtFileOffset(file, offset, (lspDocument, position) -> {
                SelectionRangeParams params = new SelectionRangeParams(lspDocument.getId(), List.of(position));
                // Healthy servers respond instantly, typically much faster than 100 ms. A larger timeout may cause unwanted modal progress dialog
                List<SelectionRange> result = sendRequestSync(100, it -> it.getTextDocumentService().selectionRange(params));
                // SelectionRangeParams include exactly one Position, so the result should contain at most one SelectionRange
                if (result == null || result.isEmpty()) {
                    return null;
                }
                return LspResponseMappingKt.mapSelectionRange(lspDocument, result.get(0));
            })
        );
    }

    /**
     * Must be called on a background thread.
     */
    @RequiredReadAction
    public @Nullable TextRangeAndMarkupContent getHoverCaching(VirtualFile file, int offset) {
        return getHoverCaching(file, offset, LspClient.DEFAULT_REQUEST_TIMEOUT_MS);
    }

    /**
     * Must be called on a background thread.
     */
    @RequiredReadAction
    public @Nullable TextRangeAndMarkupContent getHoverCaching(VirtualFile file, int offset, int timeoutMs) {
        return myHoverResultCache.getOrCompute(file, offset, () -> {
            Hover hover = myDocumentMapping.withDocumentAtFileOffset(file, offset, (lspDocument, position) -> {
                HoverParams params = new HoverParams(lspDocument.getId(), position);
                Hover response = sendRequestSync(timeoutMs, it -> it.getTextDocumentService().hover(params));
                if (response == null) {
                    return null;
                }
                Hover mapped = new Hover();
                mapped.setContents(response.getContents());
                mapped.setRange(response.getRange() != null ? lspDocument.toHostRange(response.getRange()) : null);
                return mapped;
            });
            if (hover == null) {
                return null;
            }
            Document document = FileDocumentManager.getInstance().getDocument(file);
            return TextRangeAndMarkupContent.fromHover(hover, document, offset);
        });
    }
}

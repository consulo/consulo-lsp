// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.lineMarkers;

import com.intellij.platform.lsp.api.customization.LspGoToImplementationSupport;
import com.intellij.platform.lsp.api.customization.LspInheritanceMarker;
import com.intellij.platform.lsp.api.customization.LspInheritanceMarkerKind;
import com.intellij.platform.lsp.api.customization.LspInheritanceMarkersCustomizer;
import com.intellij.platform.lsp.api.customization.LspInheritanceMarkersSupport;
import com.intellij.platform.lsp.api.customization.LspTypeHierarchySupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspDocument;
import com.intellij.platform.lsp.impl.LspDocumentMapping;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.impl.features.LspFeaturesRefreshing;
import com.intellij.platform.lsp.impl.features.documentSymbol.ConvertersKt;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspHighlightingCache;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspPullResult;
import consulo.annotation.access.RequiredReadAction;
import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.application.util.registry.Registry;
import consulo.logging.Logger;
import consulo.util.lang.Pair;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Pulls the inheritance markers for a file.
 * <p>
 * Traffic gates keep the per-symbol request count low:
 * <ul>
 * <li>An interface member is queried always. It is where a marker is the most likely.</li>
 * <li>A class method is queried only after {@code typeHierarchy/subtypes} confirmed that the class has subtypes,
 *   because the method's overriders can live only in the subtypes.</li>
 * <li>A top-level function, and a method of any other container, are not queried.</li>
 * </ul>
 * <p>
 * Consulo: the coroutine scope of a pull is a {@link RequestScope} that cancels every request of the pull when the
 * future returned by {@link #sendRequest} is cancelled; the coroutine {@code Semaphore} is {@link AsyncSemaphore}.
 */
public class LspInheritanceMarkersCache extends LspHighlightingCache<LspInheritanceMarker> {
    private static final Logger LOG = Logger.getInstance(LspInheritanceMarkersCache.class);

    private static final int MAX_PARALLEL_REQUESTS = 4;

    private final LspClientImpl myLspClient;

    private final Set<VirtualFile> myCapLoggedFiles = new HashSet<>();

    public LspInheritanceMarkersCache(LspClientImpl lspClient) {
        super(lspClient.getProject());
        myLspClient = lspClient;
    }

    /**
     * One pull costs one {@code documentSymbol} request plus one request per queried symbol,
     * so the delay is longer than {@link #LOW_PRIORITY_QUIESCENCE_DELAY}.
     */
    @Override
    protected Duration getQuiescenceDelay() {
        return Duration.ofMillis(Registry.intValue("lsp.inheritance.markers.quiescence.ms", 1000));
    }

    @Override
    @RequiredReadAction
    public boolean isSupportedForFile(VirtualFile file) {
        LspInheritanceMarkersCustomizer customizer = myLspClient.getDescriptor().getLspCustomization().getInheritanceMarkersCustomizer();
        return customizer instanceof LspInheritanceMarkersSupport support &&
            support.shouldAskServerForMarkers(file) &&
            LspServerCapabilitiesKt.supportsDocumentSymbol(myLspClient, file) &&
            (implementationRequestsAllowed(file) || typeHierarchyRequestsAllowed(file));
    }

    /**
     * The per-symbol requests respect the customizers that gate the same requests for the other features.
     */
    private boolean implementationRequestsAllowed(VirtualFile file) {
        return LspServerCapabilitiesKt.supportsGotoImplementation(myLspClient, file) &&
            myLspClient.getDescriptor().getLspCustomization().getGoToImplementationCustomizer() instanceof LspGoToImplementationSupport;
    }

    private boolean typeHierarchyRequestsAllowed(VirtualFile file) {
        return LspServerCapabilitiesKt.supportsTypeHierarchy(myLspClient, file) &&
            myLspClient.getDescriptor().getLspCustomization().getTypeHierarchyCustomizer() instanceof LspTypeHierarchySupport;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected CompletableFuture<LspPullResult<LspInheritanceMarker>> sendRequest(VirtualFile file) {
        if (!(myLspClient.getDescriptor().getLspCustomization().getInheritanceMarkersCustomizer()
            instanceof LspInheritanceMarkersSupport customizer)) {
            return CompletableFuture.completedFuture((LspPullResult<LspInheritanceMarker>) (LspPullResult<?>) LspPullResult.Failed.INSTANCE);
        }
        boolean implementationAllowed = implementationRequestsAllowed(file);
        boolean typeHierarchyAllowed = typeHierarchyRequestsAllowed(file);

        RequestScope scope = new RequestScope();
        CompletableFuture<List<@Nullable List<Pair<Range, LspInheritanceMarker>>>> perDocument =
            myLspClient.getDocumentMapping().forEachDocumentInFile(file, lspDocument -> {
                DocumentSymbolParams params = new DocumentSymbolParams(lspDocument.getId());
                CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> request =
                    scope.track(myLspClient.sendRequest(it -> it.getTextDocumentService().documentSymbol(params)));
                return request.thenCompose(response -> {
                    if (response == null) {
                        return CompletableFuture.<@Nullable List<Pair<Range, LspInheritanceMarker>>>completedFuture(null);
                    }

                    SymbolScope symbolScope = selectSymbols(
                        customizer,
                        ConvertersKt.toDocumentSymbols(response),
                        implementationAllowed,
                        typeHierarchyAllowed
                    );
                    int candidateCount = symbolScope.myInterfaceMembers.size() + symbolScope.myContainers.size() +
                        symbolScope.myClassMembers.size();
                    if (candidateCount > Registry.intValue("lsp.inheritance.markers.max.symbols", 200)) {
                        logCapSkip(file, candidateCount);
                        return CompletableFuture.<@Nullable List<Pair<Range, LspInheritanceMarker>>>completedFuture(List.of());
                    }

                    return computeMarkers(lspDocument, symbolScope, scope);
                });
            });
        CompletableFuture<LspPullResult<LspInheritanceMarker>> result = perDocument.thenApply(LspDocumentMapping::aggregateToPullResult);
        result.whenComplete((r, t) -> {
            if (result.isCancelled()) {
                perDocument.cancel(true);
                scope.cancel();
            }
        });
        return result;
    }

    private SymbolScope selectSymbols(
        LspInheritanceMarkersSupport customizer,
        List<DocumentSymbol> roots,
        boolean implementationAllowed,
        boolean typeHierarchyAllowed
    ) {
        List<SymbolWithParent> allSymbols = flattenWithParent(roots);
        List<SymbolWithParent> interfaceMembers = new ArrayList<>();
        if (implementationAllowed) {
            for (SymbolWithParent it : allSymbols) {
                DocumentSymbol parent = it.getParent();
                if (parent != null && parent.getKind() == SymbolKind.Interface && customizer.isMethodLikeSymbol(it.getSymbol().getKind())) {
                    interfaceMembers.add(it);
                }
            }
        }
        List<SymbolWithParent> containers = new ArrayList<>();
        if (typeHierarchyAllowed) {
            for (SymbolWithParent it : allSymbols) {
                if (customizer.isClassLikeSymbol(it.getSymbol().getKind())) {
                    containers.add(it);
                }
            }
        }
        List<SymbolWithParent> classMembers = new ArrayList<>();
        if (implementationAllowed && typeHierarchyAllowed) {
            for (SymbolWithParent it : allSymbols) {
                DocumentSymbol parent = it.getParent();
                if (parent != null && parent.getKind() != SymbolKind.Interface &&
                    customizer.isClassLikeSymbol(parent.getKind()) && customizer.isMethodLikeSymbol(it.getSymbol().getKind())) {
                    classMembers.add(it);
                }
            }
        }
        return new SymbolScope(interfaceMembers, containers, classMembers);
    }

    private CompletableFuture<List<Pair<Range, LspInheritanceMarker>>> computeMarkers(
        LspDocument lspDocument,
        SymbolScope scope,
        RequestScope requestScope
    ) {
        AsyncSemaphore semaphore = new AsyncSemaphore(MAX_PARALLEL_REQUESTS);
        List<CompletableFuture<List<Pair<Range, LspInheritanceMarker>>>> work = new ArrayList<>();

        for (SymbolWithParent it : scope.myInterfaceMembers) {
            DocumentSymbol parent = it.getParent();
            work.add(computeMethodMarker(lspDocument, it.getSymbol(), parent != null ? parent.getKind() : null, semaphore, requestScope)
                .thenApply(marker -> {
                    List<Pair<Range, LspInheritanceMarker>> markers = marker != null ? List.of(marker) : List.of();
                    return markers;
                }));
        }

        Map<@Nullable DocumentSymbol, List<SymbolWithParent>> classMembersByContainer = new LinkedHashMap<>();
        for (SymbolWithParent it : scope.myClassMembers) {
            classMembersByContainer.computeIfAbsent(it.getParent(), k -> new ArrayList<>()).add(it);
        }
        for (SymbolWithParent container : scope.myContainers) {
            DocumentSymbol containerSymbol = container.getSymbol();
            CompletableFuture<@Nullable Pair<Range, LspInheritanceMarker>> classMarkerFuture = semaphore.withPermit(
                () -> computeSafely(() -> computeClassDownMarker(lspDocument, containerSymbol, requestScope))
            );
            work.add(classMarkerFuture.thenCompose(classMarker -> {
                if (classMarker == null) {
                    return CompletableFuture.<List<Pair<Range, LspInheritanceMarker>>>completedFuture(List.of());
                }
                // The container has subtypes, so its methods can have overriders.
                List<SymbolWithParent> members = classMembersByContainer.getOrDefault(containerSymbol, List.of());
                List<CompletableFuture<@Nullable Pair<Range, LspInheritanceMarker>>> memberFutures = new ArrayList<>(members.size());
                for (SymbolWithParent member : members) {
                    DocumentSymbol parent = member.getParent();
                    memberFutures.add(computeMethodMarker(
                        lspDocument,
                        member.getSymbol(),
                        parent != null ? parent.getKind() : null,
                        semaphore,
                        requestScope
                    ));
                }
                return CompletableFuture.allOf(memberFutures.toArray(new CompletableFuture[0])).thenApply(ignored -> {
                    List<Pair<Range, LspInheritanceMarker>> result = new ArrayList<>();
                    result.add(classMarker);
                    for (CompletableFuture<@Nullable Pair<Range, LspInheritanceMarker>> memberFuture : memberFutures) {
                        Pair<Range, LspInheritanceMarker> marker = memberFuture.join();
                        if (marker != null) {
                            result.add(marker);
                        }
                    }
                    return result;
                });
            }));
        }

        return CompletableFuture.allOf(work.toArray(new CompletableFuture[0])).thenApply(ignored -> {
            List<Pair<Range, LspInheritanceMarker>> result = new ArrayList<>();
            for (CompletableFuture<List<Pair<Range, LspInheritanceMarker>>> it : work) {
                result.addAll(it.join());
            }
            return result;
        });
    }

    private CompletableFuture<@Nullable Pair<Range, LspInheritanceMarker>> computeMethodMarker(
        LspDocument lspDocument,
        DocumentSymbol symbol,
        @Nullable SymbolKind parentKind,
        AsyncSemaphore semaphore,
        RequestScope requestScope
    ) {
        return semaphore.withPermit(() -> computeSafely(() -> computeMethodDownMarker(lspDocument, symbol, parentKind, requestScope)));
    }

    /**
     * Isolates one marker computation, so one failure does not cancel the sibling computations.
     */
    private <T> CompletableFuture<@Nullable T> computeSafely(Supplier<CompletableFuture<@Nullable T>> compute) {
        CompletableFuture<@Nullable T> future;
        try {
            future = compute.get();
        }
        catch (CancellationException e) {
            throw e;
        }
        catch (Exception e) {
            LOG.warn("LSP inheritance marker computation failed", e);
            return CompletableFuture.completedFuture(null);
        }
        return future.handle((value, throwable) -> {
            if (throwable == null) {
                return value;
            }
            Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null ? throwable.getCause() : throwable;
            if (cause instanceof CancellationException cancellation) {
                throw cancellation;
            }
            LOG.warn("LSP inheritance marker computation failed", cause);
            return null;
        });
    }

    private CompletableFuture<@Nullable Pair<Range, LspInheritanceMarker>> computeMethodDownMarker(
        LspDocument lspDocument,
        DocumentSymbol symbol,
        @Nullable SymbolKind parentKind,
        RequestScope requestScope
    ) {
        return requestScope.track(
            LspInheritanceMarkersRequestsKt.requestImplementationTargets(myLspClient, lspDocument, symbol.getSelectionRange().getStart())
        ).thenApply(targets -> {
            if (targets == null) {
                return null;
            }
            LspInheritanceMarkerKind kind = parentKind == SymbolKind.Interface
                ? LspInheritanceMarkerKind.IMPLEMENTED_METHOD
                : LspInheritanceMarkerKind.OVERRIDDEN_METHOD;
            return createMarker(lspDocument, symbol, kind, targets);
        });
    }

    private CompletableFuture<@Nullable Pair<Range, LspInheritanceMarker>> computeClassDownMarker(
        LspDocument lspDocument,
        DocumentSymbol symbol,
        RequestScope requestScope
    ) {
        return requestScope.track(
            LspInheritanceMarkersRequestsKt.requestSubtypeTargets(myLspClient, lspDocument, symbol.getSelectionRange().getStart())
        ).thenApply(targets -> {
            if (targets == null) {
                return null;
            }
            LspInheritanceMarkerKind kind = symbol.getKind() == SymbolKind.Interface
                ? LspInheritanceMarkerKind.IMPLEMENTED_CLASS
                : LspInheritanceMarkerKind.SUBCLASSED_CLASS;
            return createMarker(lspDocument, symbol, kind, targets);
        });
    }

    private @Nullable Pair<Range, LspInheritanceMarker> createMarker(
        LspDocument lspDocument,
        DocumentSymbol symbol,
        LspInheritanceMarkerKind kind,
        List<Location> targets
    ) {
        Range hostAnchorRange = lspDocument.toHostRange(symbol.getSelectionRange());
        List<Location> hostTargets = LspInheritanceMarkersRequestsKt.mapTargetsToHost(myLspClient, lspDocument, hostAnchorRange.getStart(), targets);
        if (hostTargets.isEmpty()) {
            return null;
        }
        return Pair.create(hostAnchorRange, new LspInheritanceMarker(kind, symbol.getName(), symbol.getKind(), hostTargets));
    }

    private void logCapSkip(VirtualFile file, int symbolCount) {
        boolean firstTime;
        synchronized (myCapLoggedFiles) {
            firstTime = myCapLoggedFiles.add(file);
        }
        if (firstTime) {
            LOG.info("Skipped the LSP inheritance markers for " + file.getName() + ": " +
                symbolCount + " symbols exceed the lsp.inheritance.markers.max.symbols limit");
        }
    }

    @Override
    protected CompletableFuture<@Nullable Void> onResponseReceived(VirtualFile file) {
        LspFeaturesRefreshing.refreshLineMarkers(myLspClient.getProject(), file);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    protected void clearAdditionalCache() {
        synchronized (myCapLoggedFiles) {
            myCapLoggedFiles.clear();
        }
    }

    @Override
    protected void clearAdditionalCache(VirtualFile file) {
        synchronized (myCapLoggedFiles) {
            myCapLoggedFiles.remove(file);
        }
    }

    private static final class SymbolWithParent {
        private final DocumentSymbol mySymbol;
        private final @Nullable DocumentSymbol myParent;

        private SymbolWithParent(DocumentSymbol symbol, @Nullable DocumentSymbol parent) {
            mySymbol = symbol;
            myParent = parent;
        }

        public DocumentSymbol getSymbol() {
            return mySymbol;
        }

        public @Nullable DocumentSymbol getParent() {
            return myParent;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return this == o
                || o instanceof SymbolWithParent that && mySymbol.equals(that.mySymbol) && Objects.equals(myParent, that.myParent);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mySymbol, myParent);
        }

        @Override
        public String toString() {
            return "SymbolWithParent(symbol=" + mySymbol + ", parent=" + myParent + ")";
        }
    }

    private static final class SymbolScope {
        private final List<SymbolWithParent> myInterfaceMembers;
        private final List<SymbolWithParent> myContainers;
        private final List<SymbolWithParent> myClassMembers;

        private SymbolScope(
            List<SymbolWithParent> interfaceMembers,
            List<SymbolWithParent> containers,
            List<SymbolWithParent> classMembers
        ) {
            myInterfaceMembers = interfaceMembers;
            myContainers = containers;
            myClassMembers = classMembers;
        }
    }

    private static List<SymbolWithParent> flattenWithParent(List<DocumentSymbol> symbols) {
        List<SymbolWithParent> result = new ArrayList<>();
        for (DocumentSymbol symbol : symbols) {
            visit(symbol, null, result);
        }
        return result;
    }

    private static void visit(DocumentSymbol symbol, @Nullable DocumentSymbol parent, List<SymbolWithParent> result) {
        result.add(new SymbolWithParent(symbol, parent));
        List<DocumentSymbol> children = symbol.getChildren();
        if (children != null) {
            for (DocumentSymbol child : children) {
                visit(child, symbol, result);
            }
        }
    }

    /**
     * Consulo: replacement of the {@code coroutineScope} of one pull. Every request of the pull is registered here,
     * so cancelling the pull cancels them (and sends {@code $/cancelRequest}).
     */
    private static final class RequestScope {
        private final List<CompletableFuture<?>> myFutures = new ArrayList<>();
        private boolean myCancelled;

        <F extends CompletableFuture<?>> F track(F future) {
            boolean cancelNow;
            synchronized (this) {
                cancelNow = myCancelled;
                if (!cancelNow) {
                    myFutures.removeIf(CompletableFuture::isDone);
                    myFutures.add(future);
                }
            }
            if (cancelNow) {
                future.cancel(true);
            }
            return future;
        }

        void cancel() {
            List<CompletableFuture<?>> futures;
            synchronized (this) {
                myCancelled = true;
                futures = new ArrayList<>(myFutures);
                myFutures.clear();
            }
            for (CompletableFuture<?> future : futures) {
                future.cancel(true);
            }
        }
    }

    /**
     * Consulo: replacement of the coroutine {@code Semaphore.withPermit} for {@link CompletableFuture}-based blocks.
     * A released permit starts the next waiting block on the application executor.
     */
    private static final class AsyncSemaphore {
        private final ArrayDeque<Runnable> myWaiters = new ArrayDeque<>();
        private int myPermits;

        AsyncSemaphore(int permits) {
            myPermits = permits;
        }

        <T> CompletableFuture<T> withPermit(Supplier<CompletableFuture<T>> block) {
            CompletableFuture<T> result = new CompletableFuture<>();
            Runnable start = () -> {
                CompletableFuture<T> future;
                try {
                    future = block.get();
                }
                catch (Throwable t) {
                    future = CompletableFuture.failedFuture(t);
                }
                future.whenComplete((value, throwable) -> {
                    release();
                    if (throwable != null) {
                        result.completeExceptionally(throwable);
                    }
                    else {
                        result.complete(value);
                    }
                });
            };

            boolean runNow;
            synchronized (this) {
                if (myPermits > 0) {
                    myPermits--;
                    runNow = true;
                }
                else {
                    myWaiters.add(start);
                    runNow = false;
                }
            }
            if (runNow) {
                start.run();
            }
            return result;
        }

        private void release() {
            Runnable next;
            synchronized (this) {
                next = myWaiters.poll();
                if (next == null) {
                    myPermits++;
                }
            }
            if (next != null) {
                AppExecutorUtil.getAppExecutorService().execute(next);
            }
        }
    }
}

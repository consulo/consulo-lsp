// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspPullResult;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.application.ReadAction;
import consulo.application.progress.ProgressManager;
import consulo.document.Document;
import consulo.document.DocumentWindow;
import consulo.document.FileDocumentManager;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.util.lang.Pair;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Maps between IDE file/offset coordinates and LSP document/position coordinates.
 * <p>
 * For regular files, this is a 1:1 mapping. For notebooks, it resolves which cell
 * the offset/range falls into and converts to cell-relative coordinates.
 * <p>
 * Manages adapter lookup and provides helper methods used by {@link LspRequestExecutor}
 * to iterate over LSP documents within a file.
 */
public class LspDocumentMapping {
    private final LspClientImpl myLspClient;

    private volatile @Nullable LspDocumentAdapter myDefaultAdapter;

    public LspDocumentMapping(LspClientImpl lspClient) {
        myLspClient = lspClient;
    }

    private LspDocumentAdapter getDefaultAdapter() {
        LspDocumentAdapter adapter = myDefaultAdapter;
        if (adapter == null) {
            adapter = new DefaultLspDocumentAdapter();
            myDefaultAdapter = adapter;
        }
        return adapter;
    }

    public LspDocumentAdapter getAdapterForFile(VirtualFile file) {
        for (LspDocumentAdapter extension : LspDocumentAdapter.EP_NAME.getExtensionList()) {
            if (extension.acceptsFile(myLspClient, file)) {
                return extension;
            }
        }
        return getDefaultAdapter();
    }

    public LspDocumentAdapter getAdapterForUrl(String url) {
        for (LspDocumentAdapter extension : LspDocumentAdapter.EP_NAME.getExtensionList()) {
            if (extension.acceptsUrl(myLspClient, url)) {
                return extension;
            }
        }
        return getDefaultAdapter();
    }

    public @Nullable LspDocument findDocumentByUrl(String targetUri) {
        return getAdapterForUrl(targetUri).getLspDocumentByUrl(myLspClient, targetUri);
    }

    public @Nullable LspDocumentPosition getDocumentPosition(VirtualFile file, Document document, int offset) {
        return getAdapterForFile(file).toLspDocumentPosition(myLspClient, file, document, offset);
    }

    @RequiredReadAction
    public List<LspDocumentRange> getDocumentRangesSync(VirtualFile file, Document document, Range range) {
        return getAdapterForFile(file).toLspDocumentRangeSync(myLspClient, file, document, range);
    }

    public List<LspDocument> getDocumentsInFileSync(VirtualFile file) {
        return getAdapterForFile(file).toLspDocumentsInFileSync(myLspClient, file);
    }

    /**
     * Resolves which LSP document the {@code offset} falls into and invokes {@code block}
     * with the document and the position within it.
     * <p>
     * For regular files, the document covers the whole file.
     * For notebooks, it resolves to the correct cell.
     */
    public <T> @Nullable T withDocumentAtOffset(VirtualFile file, Document document, int offset, BiFunction<LspDocument, Position, @Nullable T> block) {
        LspDocumentPosition documentPosition = getDocumentPosition(file, document, offset);
        return documentPosition != null ? block.apply(documentPosition.getDocument(), documentPosition.getPosition()) : null;
    }

    /**
     * Like {@link #withDocumentAtOffset}, but also unwraps language injections.
     * <p>
     * If {@code file} is a {@link VirtualFileWindow} (injected fragment),
     * the offset is translated to the host file before adapter resolution.
     * Must be called on a background thread.
     *
     * @param file   might be an injected VirtualFileWindow
     * @param offset might be an offset in the injected file
     */
    @RequiredReadAction
    public <T> @Nullable T withDocumentAtFileOffset(VirtualFile file, int offset, BiFunction<LspDocument, Position, @Nullable T> block) {
        HostCoordinates host = unwrapInjection(file, offset);
        if (host == null) {
            return null;
        }
        return withDocumentAtOffset(host.getHostFile(), host.getHostDocument(), host.getHostOffset(), block);
    }

    /**
     * Upstream: {@code suspend fun <T> forEachDocumentInFile(file, block: suspend (LspDocument) -> T): List<T>}.
     * The blocks run one after another, like the upstream sequential suspend calls.
     */
    public <T extends @Nullable Object> CompletableFuture<List<T>> forEachDocumentInFile(VirtualFile file, Function<LspDocument, CompletableFuture<T>> block) {
        CompletableFuture<List<T>> result = new CompletableFuture<>();
        getAdapterForFile(file).toLspDocumentsInFile(myLspClient, file).whenComplete((documents, throwable) -> {
            if (throwable != null) {
                result.completeExceptionally(throwable);
                return;
            }
            mapSequentially(documents, 0, new ArrayList<>(), block, result);
        });
        return result;
    }

    /**
     * Upstream: {@code suspend fun <T> forEachDocumentInFile(file, block: suspend (LspDocument, Range) -> T): List<T>}.
     * The blocks run one after another, like the upstream sequential suspend calls.
     */
    public <T extends @Nullable Object> CompletableFuture<List<T>> forEachDocumentInFile(VirtualFile file, BiFunction<LspDocument, Range, CompletableFuture<T>> block) {
        Range range = ReadAction.compute(() -> {
            Document document = FileDocumentManager.getInstance().getDocument(file);
            if (document == null) {
                return null;
            }
            return Lsp4jUtilKt.getLsp4jRange(document, 0, document.getTextLength());
        });
        if (range == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        CompletableFuture<List<T>> result = new CompletableFuture<>();
        getAdapterForFile(file).toLspDocumentRange(myLspClient, file, range).whenComplete((documentRanges, throwable) -> {
            if (throwable != null) {
                result.completeExceptionally(throwable);
                return;
            }
            mapSequentially(documentRanges, 0, new ArrayList<>(), it -> block.apply(it.getDocument(), it.getRange()), result);
        });
        return result;
    }

    /**
     * Runs {@code block} for each item, one after another. A cancellation of {@code result} cancels the in-flight block,
     * so the cancellation reaches the request like the cancellation of the upstream coroutine does.
     */
    private static <I, T> void mapSequentially(List<I> items,
                                               int index,
                                               List<T> collected,
                                               Function<I, CompletableFuture<T>> block,
                                               CompletableFuture<List<T>> result) {
        if (result.isDone()) {
            return;
        }
        if (index >= items.size()) {
            result.complete(collected);
            return;
        }
        CompletableFuture<T> future;
        try {
            future = block.apply(items.get(index));
        }
        catch (Throwable t) {
            result.completeExceptionally(t);
            return;
        }
        result.whenComplete((r, t) -> {
            if (result.isCancelled()) {
                future.cancel(true);
            }
        });
        future.whenComplete((value, throwable) -> {
            if (throwable != null) {
                result.completeExceptionally(throwable);
                return;
            }
            collected.add(value);
            mapSequentially(items, index + 1, collected, block, result);
        });
    }

    /**
     * Unwraps language injection, returning the host file, document, and offset.
     * If {@code file} is not injected, returns it as-is.
     * Must be called on a background thread.
     */
    @RequiredReadAction
    public @Nullable HostCoordinates unwrapInjection(VirtualFile file, int offset) {
        ProgressManager.checkCanceled();
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            myLspClient.logError("No document for file " + file);
            return null;
        }
        VirtualFile hostFile = file instanceof VirtualFileWindow fileWindow ? fileWindow.getDelegate() : file;
        // upstream: PsiDocumentManagerBase.getTopLevelDocument(document)
        Document hostDocument = document;
        while (hostDocument instanceof DocumentWindow documentWindow) {
            hostDocument = documentWindow.getDelegate();
        }
        int hostOffset = document instanceof DocumentWindow documentWindow ? documentWindow.injectedToHost(offset) : offset;
        return new HostCoordinates(hostFile, hostDocument, hostOffset);
    }

    public static class HostCoordinates {
        private final VirtualFile myHostFile;
        private final Document myHostDocument;
        private final int myHostOffset;

        public HostCoordinates(VirtualFile hostFile, Document hostDocument, int hostOffset) {
            myHostFile = hostFile;
            myHostDocument = hostDocument;
            myHostOffset = hostOffset;
        }

        public VirtualFile getHostFile() {
            return myHostFile;
        }

        public Document getHostDocument() {
            return myHostDocument;
        }

        public int getHostOffset() {
            return myHostOffset;
        }
    }

    /**
     * Aggregates a per-document result list (typically produced with a block that returns {@code null} to signal a missing/failed response).
     * <p>
     * Returns {@code null} only when **every** entry is {@code null} — that means no document produced a response,
     * and the cache should keep its previous state.
     */
    public static <T> @Nullable List<T> aggregatePerDocumentResults(List<? extends @Nullable List<T>> perDocument) {
        boolean allNull = true;
        for (List<T> list : perDocument) {
            if (list != null) {
                allNull = false;
                break;
            }
        }
        if (allNull) {
            return null;
        }
        List<T> result = new ArrayList<>();
        for (List<T> list : perDocument) {
            if (list != null) {
                result.addAll(list);
            }
        }
        return result;
    }

    /**
     * Like {@link #aggregatePerDocumentResults}, but wraps the outcome into an {@link LspPullResult}
     * for {@code com.intellij.platform.lsp.impl.features.highlightingCommon.LspHighlightingCache.sendRequest}.
     */
    @SuppressWarnings("unchecked")
    public static <T> LspPullResult<T> aggregateToPullResult(List<? extends @Nullable List<Pair<Range, T>>> perDocument) {
        List<Pair<Range, T>> aggregated = aggregatePerDocumentResults(perDocument);
        if (aggregated == null) {
            return (LspPullResult<T>) (LspPullResult<?>) LspPullResult.Failed.INSTANCE;
        }
        return new LspPullResult.Full<>(aggregated);
    }

    /**
     * Internal accessor for {@link LspDocumentMapping} from {@link LspClient} references in internal code.
     */
    public static LspDocumentMapping getDocumentMapping(LspClient lspClient) {
        return ((LspClientImpl) lspClient).getDocumentMapping();
    }
}

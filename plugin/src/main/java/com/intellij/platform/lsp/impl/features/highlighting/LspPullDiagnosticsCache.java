package com.intellij.platform.lsp.impl.features.highlighting;

import com.intellij.platform.lsp.api.customization.LspDiagnosticsCustomizer;
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspDocument;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspHighlightingCache;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspPullResult;
import consulo.util.lang.Pair;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DocumentDiagnosticParams;
import org.eclipse.lsp4j.RelatedFullDocumentDiagnosticReport;
import org.eclipse.lsp4j.Range;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_pullDiagnostics">textDocument/diagnostic</a>
 */
public class LspPullDiagnosticsCache extends LspHighlightingCache<LspDiagnosticAndLazyQuickFixes> {
    /**
     * Deliberately longer than {@link com.intellij.platform.lsp.api.LspClient#DEFAULT_REQUEST_TIMEOUT_MS}:
     * a server that compiles the project before it answers {@code textDocument/diagnostic} legitimately exceeds 10 s.
     */
    private static final Duration PULL_DIAGNOSTICS_TIMEOUT = Duration.ofSeconds(60);

    private final LspClientImpl myLspClient;

    /**
     * The {@code resultId} of the last accepted diagnostic report, keyed by the LSP document URI.
     * The next pull sends it as {@code DocumentDiagnosticParams.previousResultId},
     * so the server can answer with a cheap "unchanged" report instead of a full recomputation.
     */
    private final Map<VirtualFile, Map<String, String>> myFileToResultIds = Collections.synchronizedMap(new HashMap<>());

    public LspPullDiagnosticsCache(LspClientImpl lspClient) {
        super(lspClient.getProject());
        myLspClient = lspClient;
    }

    @Override
    protected Duration getQuiescenceDelay() {
        return DIAGNOSTICS_QUIESCENCE_DELAY;
    }

    @Override
    public boolean isSupportedForFile(VirtualFile file) {
        LspDiagnosticsCustomizer diagnosticsCustomizer = myLspClient.getDescriptor().getLspCustomization().getDiagnosticsCustomizer();
        return diagnosticsCustomizer instanceof LspDiagnosticsSupport support
            && support.shouldAskServerForDiagnostics(file)
            && LspServerCapabilitiesKt.supportsPullDiagnostics(myLspClient, file);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected CompletableFuture<LspPullResult<LspDiagnosticAndLazyQuickFixes>> sendRequest(VirtualFile file) {
        Map<String, String> previousResultIds = myFileToResultIds.get(file);
        CompletableFuture<LspPullResult<LspDiagnosticAndLazyQuickFixes>> request =
            doSendRequest(file, previousResultIds != null ? previousResultIds : Map.of());

        CompletableFuture<LspPullResult<LspDiagnosticAndLazyQuickFixes>> result = request.copy()
            .orTimeout(PULL_DIAGNOSTICS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            .handle((value, error) -> {
                if (error == null) {
                    return value;
                }
                if (unwrap(error) instanceof TimeoutException) {
                    // withTimeoutOrNull: cancel the request and report a failure
                    request.cancel(true);
                    return (LspPullResult<LspDiagnosticAndLazyQuickFixes>) LspPullResult.Failed.INSTANCE;
                }
                throw error instanceof CompletionException ce ? ce : new CompletionException(error);
            });
        // cancelling the returned future cancels the server request
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                request.cancel(true);
            }
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<LspPullResult<LspDiagnosticAndLazyQuickFixes>> doSendRequest(
        VirtualFile file,
        Map<String, String> previousResultIds
    ) {
        Map<String, String> newResultIds = Collections.synchronizedMap(new HashMap<>());
        CompletableFuture<List<@Nullable DocumentPullOutcome>> outcomes = myLspClient.getDocumentMapping().forEachDocumentInFile(
            file,
            lspDocument -> pullDocumentDiagnostics(lspDocument, previousResultIds.get(lspDocument.getId().getUri()), newResultIds)
        );

        return outcomes.<LspPullResult<LspDiagnosticAndLazyQuickFixes>>thenCompose(list -> {
            List<DocumentPullOutcome> received = new ArrayList<>();
            for (DocumentPullOutcome outcome : list) {
                if (outcome != null) {
                    received.add(outcome);
                }
            }

            if (received.isEmpty()) {
                return CompletableFuture.<LspPullResult<LspDiagnosticAndLazyQuickFixes>>completedFuture(LspPullResult.Failed.INSTANCE);
            }
            if (received.stream().allMatch(it -> it instanceof DocumentPullOutcome.Unchanged)) {
                return CompletableFuture.<LspPullResult<LspDiagnosticAndLazyQuickFixes>>completedFuture(new LspPullResult.Unchanged<>(() -> {
                    Map<String, String> merged = new HashMap<>(previousResultIds);
                    merged.putAll(newResultIds);
                    myFileToResultIds.put(file, merged);
                }));
            }
            if (received.stream().anyMatch(it -> it instanceof DocumentPullOutcome.Unchanged) && !previousResultIds.isEmpty()) {
                // Mixed "full" and "unchanged" reports. This is possible only for a multi-document file, for example a notebook.
                // The cache stores one aggregated snapshot per file, so re-pull every document in full.
                // The stored result ids stay in place until the retry's report is accepted.
                // The previousResultIds.isNotEmpty() condition bounds the recursion: the retry sends no ids,
                // so a mixed answer to the retry itself (non-conforming) falls through to the full branch.
                return doSendRequest(file, Map.of());
            }

            List<Pair<Range, LspDiagnosticAndLazyQuickFixes>> items = new ArrayList<>();
            for (DocumentPullOutcome outcome : received) {
                if (outcome instanceof DocumentPullOutcome.Full full) {
                    items.addAll(full.getItems());
                }
            }
            return CompletableFuture.<LspPullResult<LspDiagnosticAndLazyQuickFixes>>completedFuture(
                new LspPullResult.Full<>(items, () -> myFileToResultIds.put(file, newResultIds))
            );
        });
    }

    private CompletableFuture<@Nullable DocumentPullOutcome> pullDocumentDiagnostics(
        LspDocument lspDocument,
        @Nullable String previousResultId,
        Map<String, String> newResultIds
    ) {
        DocumentDiagnosticParams params = new DocumentDiagnosticParams(lspDocument.getId());
        params.setPreviousResultId(previousResultId);
        return myLspClient.sendRequest(server -> server.getTextDocumentService().diagnostic(params)).<@Nullable DocumentPullOutcome>thenApply(result -> {
            if (result == null) {
                return null;
            }
            String uri = lspDocument.getId().getUri();

            if (result.isRelatedUnchangedDocumentDiagnosticReport()) {
                if (previousResultId == null) {
                    // Non-conforming, but tolerated: the server says the current results are valid.
                    // A failure here would re-pull in a loop on every trigger.
                    myLspClient.logWarn(
                        "RelatedUnchangedDocumentDiagnosticReport response is not expected because DocumentDiagnosticParams.previousResultId has not been set");
                }
                // The @NonNull annotation on resultId is not enforced during deserialization.
                String resultId = result.getRelatedUnchangedDocumentDiagnosticReport().getResultId();
                if (resultId != null) {
                    newResultIds.put(uri, resultId);
                }
                return DocumentPullOutcome.Unchanged.INSTANCE;
            }

            RelatedFullDocumentDiagnosticReport report = result.getRelatedFullDocumentDiagnosticReport();
            if (report.getResultId() != null) {
                newResultIds.put(uri, report.getResultId());
            }
            List<Pair<Range, LspDiagnosticAndLazyQuickFixes>> items = new ArrayList<>(report.getItems().size());
            for (Diagnostic diagnostic : report.getItems()) {
                items.add(Pair.create(lspDocument.toHostRange(diagnostic.getRange()),
                    new LspDiagnosticAndLazyQuickFixes(diagnostic, lspDocument.getId())));
            }
            return new DocumentPullOutcome.Full(items);
        });
    }

    /**
     * Drops the stored result ids for {@code file}, so the forced re-pull returns a full report instead of an "unchanged"
     * one, and marks the cached results stale while keeping them visible.
     */
    @Override
    public void forceFullRepull(VirtualFile file) {
        myFileToResultIds.remove(file);
        invalidate(file);
    }

    @Override
    protected void clearAdditionalCache() {
        myFileToResultIds.clear();
    }

    @Override
    protected void clearAdditionalCache(VirtualFile file) {
        myFileToResultIds.remove(file);
    }

    @Override
    protected CompletableFuture<@Nullable Void> onResponseReceived(VirtualFile file) {
        LspHighlightingApplier.getInstance(myLspClient.getProject()).scheduleHighlightingRefresh(file);
        myLspClient.notifyDiagnosticsReceived(file);
        return CompletableFuture.completedFuture(null);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private sealed interface DocumentPullOutcome permits DocumentPullOutcome.Unchanged, DocumentPullOutcome.Full {
        final class Unchanged implements DocumentPullOutcome {
            static final Unchanged INSTANCE = new Unchanged();

            private Unchanged() {
            }
        }

        final class Full implements DocumentPullOutcome {
            private final List<Pair<Range, LspDiagnosticAndLazyQuickFixes>> myItems;

            Full(List<Pair<Range, LspDiagnosticAndLazyQuickFixes>> items) {
                myItems = items;
            }

            List<Pair<Range, LspDiagnosticAndLazyQuickFixes>> getItems() {
                return myItems;
            }
        }
    }
}

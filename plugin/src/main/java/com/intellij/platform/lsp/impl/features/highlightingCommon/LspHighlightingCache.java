package com.intellij.platform.lsp.impl.features.highlightingCommon;

import com.intellij.platform.lsp.impl.cache.LspCache;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.application.Application;
import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.document.event.DocumentEvent;
import consulo.document.util.TextRange;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.collection.MultiMap;
import consulo.util.lang.Pair;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Range;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Helps to keep reasonable highlighting ranges for edited files until updated info arrives from the server.
 * <p>
 * Staleness is per document. The snapshot stores the {@code Document.modificationStamp} that the request was
 * sent for. {@link #getHighlightings} re-requests only when the current stamp differs, and a response is
 * accepted only when the stamp still matches. A change in another file does not touch this file's
 * cache: the server learns about that change from its own {@code didChange} notification, and a server-side
 * cross-file effect arrives as a server-forced refresh, which {@link #invalidate} turns into a re-request.
 * <p>
 * The request pipeline is a {@link CompletableFuture} chain on the application executors; each request is tracked by a
 * {@link RequestJob}.
 */
public abstract class LspHighlightingCache<T> implements LspCache {
    private static final Logger LOG = Logger.getInstance(LspHighlightingCache.class);

    private static final long STALE_DOC_MOD_STAMP = -1L;

    /**
     * Diagnostics are what the user waits for, so their debounce is shorter than {@link #LOW_PRIORITY_QUIESCENCE_DELAY}.
     * The delay must still absorb the typing cadence. A fluent typist produces a keystroke every 100-200 ms,
     * so a shorter delay starts a pull at almost every keystroke and cancels it at the next one.
     * With 250 ms the pull starts at a natural pause, for example at a word boundary.
     */
    public static final Duration DIAGNOSTICS_QUIESCENCE_DELAY = Duration.ofMillis(250);

    /**
     * Semantic tokens, document links, folding, code lens, inlay hints, and colors are cosmetic while
     * the user types. Hold them back until the document settles, so the server can serve {@code didChange},
     * completion, and diagnostics first.
     */
    public static final Duration LOW_PRIORITY_QUIESCENCE_DELAY = Duration.ofMillis(300);

    /**
     * Widens or disables the quiescence window in tests, where the production values are too
     * timing-sensitive to assert against.
     */
    private static volatile @Nullable Duration ourQuiescenceDelayOverride = null;

    protected final Project myProject;

    private final Map<VirtualFile, CachedHighlightingsSnapshot<T>> myFileToCachedHighlightingsSnapshot = new HashMap<>();
    private final MultiMap<VirtualFile, PendingEdit> myFileToPendingEdits = new MultiMap<>();
    private final Map<VirtualFile, Long> myFileToStampWhenRequestSent = new HashMap<>();
    private final Map<VirtualFile, RequestJob> myFileToInFlightRequest = new HashMap<>();

    protected LspHighlightingCache(Project project) {
        myProject = project;
    }

    protected Project getProject() {
        return myProject;
    }

    public static @Nullable Duration getQuiescenceDelayOverride() {
        return ourQuiescenceDelayOverride;
    }

    public static void setQuiescenceDelayOverride(@Nullable Duration quiescenceDelayOverride) {
        ourQuiescenceDelayOverride = quiescenceDelayOverride;
    }

    /**
     * How long the requested document must stay stable before a pull is sent.
     * Rapid consecutive edits then converge on one final stamp, and the dedup guard in
     * {@link #scheduleHighlightingsUpdate} collapses the triggers into a single server request.
     * Without the delay, an edit burst produces a send, an immediate {@code $/cancelRequest}, and a re-send.
     * <p>
     * The first pull for a file (nothing cached, nothing in flight) skips the delay,
     * so the file-open latency is unaffected.
     */
    protected Duration getQuiescenceDelay() {
        return LOW_PRIORITY_QUIESCENCE_DELAY;
    }

    /**
     * Whether this cache requests data from the server. {@code false} for a cache fed by server notifications.
     */
    public boolean getSupportsPull() {
        return true;
    }

    @RequiredReadAction
    public abstract boolean isSupportedForFile(VirtualFile file);

    /**
     * Must be called from a background thread.
     */
    @RequiredReadAction
    public List<LspCachedHighlighting<T>> getHighlightings(VirtualFile file) {
        if (!isSupportedForFile(file)) {
            return List.of();
        }

        synchronized (this) {
            CachedHighlightingsSnapshot<T> highlightingsSnapshot = myFileToCachedHighlightingsSnapshot.get(file);

            Document cachedDocument = FileDocumentManager.getInstance().getCachedDocument(file);
            Long docModStamp = cachedDocument != null ? cachedDocument.getModificationStamp() : null;
            if (getSupportsPull() && docModStamp != null
                && (highlightingsSnapshot == null || highlightingsSnapshot.myDocModStamp != docModStamp)) {
                scheduleHighlightingsUpdate(file);
            }

            if (highlightingsSnapshot == null || highlightingsSnapshot.myCachedHighlightings.isEmpty()) {
                return List.of();
            }

            List<LspCachedHighlighting<T>> updatedHighlightings = applyPendingEdits(file, highlightingsSnapshot.myCachedHighlightings);

            myFileToCachedHighlightingsSnapshot.put(
                file,
                new CachedHighlightingsSnapshot<>(highlightingsSnapshot.myDocModStamp, updatedHighlightings)
            );
            myFileToPendingEdits.remove(file);

            return updatedHighlightings;
        }
    }

    private void scheduleHighlightingsUpdate(VirtualFile file) {
        RequestJob job = new RequestJob();

        settleRequestStamp(file)
            .<@Nullable Void>thenComposeAsync(docModStamp -> {
                if (docModStamp == null) {
                    return CompletableFuture.completedFuture(null); // no document => nothing to highlight
                }
                return sendRequestAndApply(file, docModStamp, job);
            }, AppExecutorUtil.getAppExecutorService())
            .whenComplete((ignored, error) -> {
                if (error != null) {
                    Throwable cause = unwrap(error);
                    if (!(cause instanceof CancellationException)) {
                        LOG.warn("LSP highlighting request failed for " + file.getName(), cause);
                    }
                }
            });
    }

    private CompletableFuture<@Nullable Void> sendRequestAndApply(VirtualFile file, long docModStamp, RequestJob job) {
        synchronized (this) {
            CachedHighlightingsSnapshot<T> snapshot = myFileToCachedHighlightingsSnapshot.get(file);
            if (snapshot != null && snapshot.myDocModStamp == docModStamp) {
                // a response for the same document version has been applied while this trigger was settling
                return CompletableFuture.completedFuture(null);
            }
            Long previousStamp = myFileToStampWhenRequestSent.put(file, docModStamp);
            if (previousStamp != null && previousStamp == docModStamp) {
                // A request for this document version has been already sent, and its response stays
                // acceptable, because acceptance is gated by the document stamp. The daemon restarts on
                // every PSI tick, so this dedup also keeps the running request alive across a change in
                // another file. A server-forced refresh does not take this path: [invalidate] drops the
                // stamp and cancels the request.
                return CompletableFuture.completedFuture(null);
            }
            // The previous in-flight request was sent for another document version: cancel it, so the server
            // stops working on it ($/cancelRequest), and its late response does not queue this request behind it.
            RequestJob previousJob = myFileToInFlightRequest.put(file, job);
            if (previousJob != null) {
                previousJob.cancel();
            }
        }

        CompletableFuture<LspPullResult<T>> request;
        try {
            request = job.track(sendRequest(file));
        }
        catch (Throwable e) {
            request = CompletableFuture.failedFuture(e);
        }

        return request
            .<@Nullable Void>thenComposeAsync(result -> {
                job.ensureActive();
                if (result instanceof LspPullResult.Full<T> full) {
                    return responseReceived(file, docModStamp, full, job);
                }
                else if (result instanceof LspPullResult.Unchanged<T> unchanged) {
                    return markSnapshotFresh(file, docModStamp, unchanged, job);
                }
                return CompletableFuture.<@Nullable Void>completedFuture(null);
            }, AppExecutorUtil.getAppExecutorService())
            .whenComplete((ignored, error) -> {
                synchronized (this) {
                    if (myFileToInFlightRequest.get(file) == job) {
                        // Still the current request for the file: release the slot and the dedup guard,
                        // so the next getHighlightings() call can re-request.
                        // When a newer request took over - possibly with the same document stamp after a forced
                        // refresh - both entries belong to it, so leave them alone.
                        myFileToInFlightRequest.remove(file);
                        myFileToStampWhenRequestSent.remove(file, docModStamp);
                    }
                }
            });
    }

    /**
     * Reads this document's modification stamp and, except for the first pull, waits until the document
     * is stable across one full {@link #getQuiescenceDelay() quiescenceDelay}.
     */
    private CompletableFuture<@Nullable Long> settleRequestStamp(VirtualFile file) {
        return CompletableFuture.supplyAsync(() -> readDocModStamp(file), AppExecutorUtil.getAppExecutorService())
            .thenCompose(stamp -> {
                if (stamp == null) {
                    return CompletableFuture.completedFuture(null);
                }

                // The override is null in production; only tests set it.
                Duration override = ourQuiescenceDelayOverride;
                Duration quiescence = override != null ? override : getQuiescenceDelay();
                if (quiescence.compareTo(Duration.ZERO) > 0 && !isFirstPullFor(file)) {
                    return waitUntilStable(file, stamp, quiescence);
                }
                return CompletableFuture.completedFuture(stamp);
            });
    }

    private CompletableFuture<@Nullable Long> waitUntilStable(VirtualFile file, long previousStamp, Duration quiescence) {
        CompletableFuture<@Nullable Long> result = new CompletableFuture<>();
        AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
            try {
                Long stamp = readDocModStamp(file);
                if (stamp == null) {
                    result.complete(null);
                }
                else if (stamp == previousStamp) {
                    result.complete(stamp);
                }
                else {
                    waitUntilStable(file, stamp, quiescence).whenComplete((value, error) -> {
                        if (error != null) {
                            result.completeExceptionally(error);
                        }
                        else {
                            result.complete(value);
                        }
                    });
                }
            }
            catch (Throwable e) {
                result.completeExceptionally(e);
            }
        }, quiescence.toMillis(), TimeUnit.MILLISECONDS);
        return result;
    }

    private static @Nullable Long readDocModStamp(VirtualFile file) {
        return readAction(() -> {
            Document document = FileDocumentManager.getInstance().getDocument(file);
            return document != null ? document.getModificationStamp() : null;
        });
    }

    private boolean isFirstPullFor(VirtualFile file) {
        synchronized (this) {
            return myFileToCachedHighlightingsSnapshot.get(file) == null && myFileToInFlightRequest.get(file) == null;
        }
    }

    /**
     * Called on a background thread, outside the read action. The returned future is cancelled
     * when the request becomes obsolete (cancellation must propagate to the server, i.e. {@code $/cancelRequest}).
     */
    protected abstract CompletableFuture<LspPullResult<T>> sendRequest(VirtualFile file);

    /**
     * @param docModStamp the document stamp captured at the moment of sending the request to the server
     */
    private CompletableFuture<@Nullable Void> responseReceived(
        VirtualFile file,
        long docModStamp,
        LspPullResult.Full<T> result,
        RequestJob job
    ) {
        List<LspCachedHighlighting<T>> highlightings = readAction(() -> {
            Document document = FileDocumentManager.getInstance().getDocument(file);
            if (document == null) {
                return null;
            }
            if (document.getModificationStamp() != docModStamp) {
                // This document changed while the request was in flight, so the response ranges are stale.
                scheduleHighlightingsUpdate(file);
                return null;
            }
            return buildHighlightings(document, result.getItems());
        });
        if (highlightings == null) {
            return CompletableFuture.completedFuture(null);
        }

        // A forced refresh may have cancelled this request after the last suspension point.
        job.ensureActive();
        applyServerHighlightings(file, docModStamp, highlightings, result.getOnAccepted());
        return onResponseReceived(file);
    }

    /**
     * The server confirmed that the cached results are still valid (see {@link LspPullResult.Unchanged}).
     * Refreshes the snapshot's mod count, so the cache counts as fresh. Keeps the contents and the pending edits.
     */
    private CompletableFuture<@Nullable Void> markSnapshotFresh(
        VirtualFile file,
        long docModStamp,
        LspPullResult.Unchanged<T> result,
        RequestJob job
    ) {
        Boolean docUnchanged = readAction(() -> {
            Document document = FileDocumentManager.getInstance().getDocument(file);
            return document != null && document.getModificationStamp() == docModStamp;
        });
        if (!Boolean.TRUE.equals(docUnchanged)) {
            // Same acceptance rule as for a full response.
            scheduleHighlightingsUpdate(file);
            return CompletableFuture.completedFuture(null);
        }

        // A forced refresh may have cancelled this request after the last suspension point.
        job.ensureActive();
        synchronized (this) {
            // An "unchanged" report without a cached snapshot cannot be trusted. Treat it as a failure.
            CachedHighlightingsSnapshot<T> snapshot = myFileToCachedHighlightingsSnapshot.get(file);
            if (snapshot == null) {
                return CompletableFuture.completedFuture(null);
            }
            myFileToCachedHighlightingsSnapshot.put(file, new CachedHighlightingsSnapshot<>(docModStamp, snapshot.myCachedHighlightings));
            // Keep fileToPendingEdits: the kept highlightings still need the pending-edit adjustment.
            myFileToStampWhenRequestSent.remove(file, docModStamp);
            Runnable onAccepted = result.getOnAccepted();
            if (onAccepted != null) {
                onAccepted.run();
            }
        }
        return onResponseReceived(file);
    }

    protected List<LspCachedHighlighting<T>> buildHighlightings(Document document, List<Pair<Range, T>> infosFromServer) {
        List<LspCachedHighlighting<T>> result = new ArrayList<>(infosFromServer.size());
        for (Pair<Range, T> infoFromServer : infosFromServer) {
            TextRange textRange = Lsp4jUtilKt.getRangeInDocument(document, infoFromServer.getFirst());
            if (textRange == null) {
                continue;
            }
            result.add(new LspCachedHighlighting<>(textRange, infoFromServer.getSecond()));
        }
        return result;
    }

    protected void applyServerHighlightings(VirtualFile file, long docModStamp, List<LspCachedHighlighting<T>> highlightings) {
        applyServerHighlightings(file, docModStamp, highlightings, null);
    }

    /**
     * Commits a snapshot for the document version {@code docModStamp}. The caller checks acceptance first.
     */
    protected void applyServerHighlightings(
        VirtualFile file,
        long docModStamp,
        List<LspCachedHighlighting<T>> highlightings,
        @Nullable Runnable onAccepted
    ) {
        synchronized (this) {
            myFileToCachedHighlightingsSnapshot.put(file, new CachedHighlightingsSnapshot<>(docModStamp, highlightings));
            myFileToPendingEdits.remove(file);
            Long sentStamp = myFileToStampWhenRequestSent.get(file);
            if (sentStamp != null && sentStamp == docModStamp) {
                myFileToStampWhenRequestSent.remove(file);
            }
            if (onAccepted != null) {
                onAccepted.run();
            }
        }
    }

    /**
     * Called when the cache has been updated with just received information from the server.
     * Implementations may want, for example, to restart code highlighting.
     */
    protected abstract CompletableFuture<@Nullable Void> onResponseReceived(VirtualFile file);

    public void fileEdited(VirtualFile file, DocumentEvent e) {
        synchronized (this) {
            CachedHighlightingsSnapshot<T> snapshot = myFileToCachedHighlightingsSnapshot.get(file);
            if (snapshot != null && !snapshot.myCachedHighlightings.isEmpty()) {
                myFileToPendingEdits.putValue(file, new PendingEdit(e.getOffset(), e.getOldLength(), e.getNewLength()));
            }
        }
    }

    private List<LspCachedHighlighting<T>> applyPendingEdits(VirtualFile file, List<LspCachedHighlighting<T>> highlightings) {
        Collection<PendingEdit> edits = myFileToPendingEdits.get(file);
        return LspCachedHighlighting.applyPendingEdits(highlightings, edits);
    }

    @Override
    public void clearCache() {
        synchronized (this) {
            myFileToCachedHighlightingsSnapshot.clear();
            myFileToPendingEdits.clear();
            myFileToStampWhenRequestSent.clear();
            for (RequestJob job : myFileToInFlightRequest.values()) {
                job.cancel();
            }
            myFileToInFlightRequest.clear();
            clearAdditionalCache();
        }
    }

    protected void clearAdditionalCache() {
    }

    /**
     * Drops everything cached for {@code file} and cancels its running request. Called once the client has closed the file.
     * A reopened file keeps its document stamp, so the old results would look fresh,
     * while the server may answer differently by then, for example after a change in another file.
     */
    public void fileClosed(VirtualFile file) {
        synchronized (this) {
            myFileToCachedHighlightingsSnapshot.remove(file);
            myFileToPendingEdits.remove(file);
            myFileToStampWhenRequestSent.remove(file);
            RequestJob job = myFileToInFlightRequest.remove(file);
            if (job != null) {
                job.cancel();
            }
            clearAdditionalCache(file);
        }
    }

    protected void clearAdditionalCache(VirtualFile file) {
    }

    /**
     * Marks {@code file} stale like {@link #invalidate}. It also forgets the per-file protocol state that would let the server
     * answer "unchanged", a stored result id for example, because what changed is the answer itself.
     */
    public void forceFullRepull(VirtualFile file) {
        invalidate(file);
    }

    /**
     * Marks the cached results for {@code file} stale so the next {@link #getHighlightings} re-requests them from the server, while
     * keeping the current results in place until the fresh ones arrive.
     * <p>
     * Used for server-forced refreshes (e.g. {@code workspace/inlayHint/refresh}), where results change without a document edit
     * and the
     * {@link CachedHighlightingsSnapshot#myDocModStamp docModStamp} staleness check would otherwise consider the cache fresh.
     * Unlike {@link #clearCache}, reactive consumers keep showing the previous results (no flicker); the refreshed results flow
     * in through the usual {@link #onResponseReceived} path.
     */
    public void invalidate(VirtualFile file) {
        synchronized (this) {
            // An in-flight request predates the refresh. Cancel it and drop the dedup guard,
            // so the forced request is actually sent, and the stale response cannot mark the snapshot fresh
            // (that would dedup the forced request away).
            RequestJob job = myFileToInFlightRequest.remove(file);
            if (job != null) {
                job.cancel();
            }
            myFileToStampWhenRequestSent.remove(file);
            CachedHighlightingsSnapshot<T> snapshot = myFileToCachedHighlightingsSnapshot.get(file);
            if (snapshot == null) {
                return;
            }
            // STALE_DOC_MOD_STAMP never equals a real document stamp, so getHighlightings always re-requests.
            myFileToCachedHighlightingsSnapshot.put(file, new CachedHighlightingsSnapshot<>(STALE_DOC_MOD_STAMP, snapshot.myCachedHighlightings));
        }
    }

    private static <R> @Nullable R readAction(Supplier<@Nullable R> computation) {
        return Application.get().runReadAction(computation);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static final class CachedHighlightingsSnapshot<T> {
        /**
         * the {@code Document.modificationStamp} the snapshot was built for; {@link #STALE_DOC_MOD_STAMP} marks a forced refresh
         */
        private final long myDocModStamp;
        private final List<LspCachedHighlighting<T>> myCachedHighlightings;

        private CachedHighlightingsSnapshot(long docModStamp, List<LspCachedHighlighting<T>> cachedHighlightings) {
            myDocModStamp = docModStamp;
            myCachedHighlightings = cachedHighlightings;
        }
    }

    /**
     * Tracks one request. Cancelling it cancels the tracked server request future.
     */
    private static final class RequestJob {
        private volatile boolean myCancelled;
        private volatile @Nullable CompletableFuture<?> myRequest;

        <R> CompletableFuture<R> track(CompletableFuture<R> request) {
            myRequest = request;
            if (myCancelled) {
                request.cancel(true);
            }
            return request;
        }

        void cancel() {
            myCancelled = true;
            CompletableFuture<?> request = myRequest;
            if (request != null) {
                request.cancel(true);
            }
        }

        void ensureActive() {
            if (myCancelled) {
                throw new CancellationException();
            }
        }
    }
}

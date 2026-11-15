// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.cache;

import consulo.application.progress.ProgressManager;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.language.psi.PsiManager;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Single-slot memoization keyed by a {@link VirtualFile} plus a secondary key,
 * guarded by a modification stamp.
 * <p>
 * A lookup hits the cache when:
 * <ol>
 *  <li>the stored file equals the queried file, and</li>
 *  <li>the modification stamp hasn't changed since the value was stored, and</li>
 *  <li>{@code matches} returns {@code true} for the stored key/value against the queried key.</li>
 * </ol>
 * <p>
 * The stamp is the global PSI modification count by default. When the cached results depend only on the
 * requested document (for example, {@code textDocument/documentSymbol}), pass
 * {@code invalidateOnlyOnDocumentChange = true} to key on the document's own modification stamp instead.
 * A change in another file then keeps the cache valid. Consumers such as the structure view,
 * the breadcrumbs, and the sticky lines then do not re-request on every project-wide PSI tick.
 * <p>
 * By default {@code matches} is key equality. Override it to implement containment-style lookups
 * (e.g. "cursor offset still inside the cached result's text range").
 * <p>
 * Shape mirrors the LSP spec's {@code TextDocument*Params} — a file is the primary coordinate;
 * the secondary key (offset, or {@link #FILE_LEVEL_KEY} for file-level requests) lives inside.
 * <p>
 * Concurrency: {@link #getOrCompute} runs its compute function outside the cache monitor. Concurrent callers with
 * the same file and key join the in-flight computation instead of sending a duplicate request or parking on the monitor.
 * A waiter's {@link consulo.component.ProcessCanceledException} does not
 * cancel the shared computation; the owner's cancellation releases the waiters, and one of them retries.
 */
public class LspPerFileCache<K, V> implements LspCache {
    /**
     * Checks whether a stored entry matches the queried key.
     */
    @FunctionalInterface
    public interface Matcher<K, V> {
        boolean matches(K storedKey, V storedValue, K queriedKey);
    }

    /**
     * The secondary key of the file-level shape, see {@link #getOrCompute(LspPerFileCache, VirtualFile, Supplier)}.
     */
    public static final Object FILE_LEVEL_KEY = new Object() {
        @Override
        public String toString() {
            return "FILE_LEVEL_KEY";
        }
    };

    private static final long WAITER_POLL_MS = 10L;

    private static final class Slot<K, V> {
        final long myStamp;
        final VirtualFile myFile;
        /**
         * mutable: a containment-style hit re-anchors the key to the latest query
         */
        K myKey;
        final CompletableFuture<@Nullable V> myFuture;

        Slot(long stamp, VirtualFile file, K key, CompletableFuture<@Nullable V> future) {
            myStamp = stamp;
            myFile = file;
            myKey = key;
            myFuture = future;
        }
    }

    private final Project myProject;
    private final boolean myInvalidateOnlyOnDocumentChange;
    private final Matcher<K, V> myMatches;

    private @Nullable Slot<K, V> mySlot = null; // guarded by `this`

    public LspPerFileCache(Project project) {
        this(project, false);
    }

    public LspPerFileCache(Project project, boolean invalidateOnlyOnDocumentChange) {
        this(project, invalidateOnlyOnDocumentChange, (stored, storedValue, queried) -> Objects.equals(stored, queried));
    }

    public LspPerFileCache(Project project, boolean invalidateOnlyOnDocumentChange, Matcher<K, V> matches) {
        myProject = project;
        myInvalidateOnlyOnDocumentChange = invalidateOnlyOnDocumentChange;
        myMatches = matches;
    }

    /**
     * Must be called on a background thread.
     */
    public @Nullable V getOrCompute(VirtualFile file, K key, Supplier<@Nullable V> compute) {
        while (true) {
            ProgressManager.checkCanceled();

            long stamp = currentStamp(file);
            boolean owner = false;
            boolean joinedSameKey = true;
            CompletableFuture<@Nullable V> future;
            synchronized (this) {
                Slot<K, V> s = mySlot;
                CompletableFuture<@Nullable V> joined = null;
                if (s != null && s.myFile.equals(file) && s.myStamp == stamp) {
                    if (s.myFuture.isDone()) {
                        V stored = s.myFuture.isCompletedExceptionally() ? null : s.myFuture.getNow(null);
                        if (stored != null && myMatches.matches(s.myKey, stored, key)) {
                            s.myKey = key;
                            return stored;
                        }
                    }
                    else {
                        // A computation for this file and stamp is in flight. Join it: for the same key its result is
                        // the answer. For a different key the completed result may still satisfy [matches]
                        // (a containment lookup), so wait and re-evaluate instead of computing concurrently.
                        joinedSameKey = Objects.equals(s.myKey, key);
                        joined = s.myFuture;
                    }
                }
                if (joined != null) {
                    future = joined;
                }
                else {
                    CompletableFuture<@Nullable V> freshFuture = new CompletableFuture<>();
                    mySlot = new Slot<>(stamp, file, key, freshFuture);
                    owner = true;
                    future = freshFuture;
                }
            }

            if (owner) {
                V result;
                try {
                    result = compute.get();
                }
                catch (Throwable t) {
                    dropSlot(future);
                    future.completeExceptionally(t); // release the waiters; one of them retries
                    throw t;
                }
                if (result == null) {
                    dropSlot(future); // `null` is not cached; the next call recomputes
                }
                future.complete(result);
                return result;
            }

            // A waiter: poll the shared future with cancellability.
            // Do NOT cancel the shared future on this waiter's cancellation - the owner's request serves the others.
            // No own timeout: the owner's compute() is bounded by the request timeout and completes the future either way.
            while (!future.isDone()) {
                ProgressManager.checkCanceled();
                try {
                    future.get(WAITER_POLL_MS, TimeUnit.MILLISECONDS);
                    break;
                }
                catch (TimeoutException ignored) {
                    // keep waiting
                }
                catch (ExecutionException | CancellationException ignored) {
                    break;
                }
                catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            if (joinedSameKey && future.isDone() && !future.isCompletedExceptionally()) {
                return future.getNow(null);
            }
            // The owner failed, or this caller waited on a different key. Retry: the completed slot can serve
            // a containment hit; otherwise this caller becomes the new owner.
        }
    }

    /**
     * Returns the cached value for {@code file} and {@code key} when a completed computation matches them, or {@code null} otherwise.
     * This function never computes a value and never waits for a computation in flight, so it is safe on the EDT.
     */
    public @Nullable V getIfComputed(VirtualFile file, K key) {
        long stamp = currentStamp(file);
        synchronized (this) {
            Slot<K, V> s = mySlot;
            if (s == null) {
                return null;
            }
            if (!s.myFile.equals(file) || s.myStamp != stamp || !s.myFuture.isDone() || s.myFuture.isCompletedExceptionally()) {
                return null;
            }
            V stored = s.myFuture.getNow(null);
            if (stored == null) {
                return null;
            }
            if (!myMatches.matches(s.myKey, stored, key)) {
                return null;
            }
            s.myKey = key;
            return stored;
        }
    }

    private long currentStamp(VirtualFile file) {
        if (myInvalidateOnlyOnDocumentChange) {
            // Fall back to the global count when the document is not in memory:
            // bypassing the cache here would send a server request on every call.
            Document document = FileDocumentManager.getInstance().getCachedDocument(file);
            if (document != null) {
                return document.getModificationStamp();
            }
        }
        return PsiManager.getInstance(myProject).getModificationTracker().getModificationCount();
    }

    private void dropSlot(CompletableFuture<@Nullable V> expectedFuture) {
        synchronized (this) {
            Slot<K, V> s = mySlot;
            if (s != null && s.myFuture == expectedFuture) {
                mySlot = null;
            }
        }
    }

    @Override
    public synchronized void clearCache() {
        mySlot = null;
    }

    /**
     * Convenience overload for the file-level shape — no secondary key to pass.
     * Must be called on a background thread.
     */
    public static <V> @Nullable V getOrCompute(LspPerFileCache<Object, V> cache, VirtualFile file, Supplier<@Nullable V> compute) {
        return cache.getOrCompute(file, FILE_LEVEL_KEY, compute);
    }
}

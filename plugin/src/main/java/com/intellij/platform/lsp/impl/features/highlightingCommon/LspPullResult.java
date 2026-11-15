package com.intellij.platform.lsp.impl.features.highlightingCommon;

import consulo.util.lang.Pair;
import org.eclipse.lsp4j.Range;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The outcome of one {@link LspHighlightingCache#sendRequest} round trip.
 * <p>
 * Consulo: Kotlin {@code Pair<Range, T>} is {@link Pair}. {@code Unchanged} and {@code Failed} are typed
 * {@code LspPullResult<Nothing>} upstream; here they are generic, and {@link Failed#INSTANCE} is raw,
 * so it can be returned as any {@code LspPullResult<T>}.
 */
public sealed interface LspPullResult<T> permits LspPullResult.Full, LspPullResult.Unchanged, LspPullResult.Failed {
    /**
     * A full report from the server.
     */
    final class Full<T> implements LspPullResult<T> {
        private final List<Pair<Range, T>> myItems;
        private final @Nullable Runnable myOnAccepted;

        public Full(List<Pair<Range, T>> items) {
            this(items, null);
        }

        /**
         * @param onAccepted runs under the cache lock right after the snapshot is committed.
         *                   Implementations persist per-request state there, for example the pull-diagnostics result ids.
         */
        public Full(List<Pair<Range, T>> items, @Nullable Runnable onAccepted) {
            myItems = items;
            myOnAccepted = onAccepted;
        }

        public List<Pair<Range, T>> getItems() {
            return myItems;
        }

        public @Nullable Runnable getOnAccepted() {
            return myOnAccepted;
        }
    }

    /**
     * The server confirmed that the previously reported results are still valid.
     * For example, a {@code RelatedUnchangedDocumentDiagnosticReport} for {@code textDocument/diagnostic}.
     */
    final class Unchanged<T> implements LspPullResult<T> {
        private final @Nullable Runnable myOnAccepted;

        public Unchanged() {
            this(null);
        }

        public Unchanged(@Nullable Runnable onAccepted) {
            myOnAccepted = onAccepted;
        }

        public @Nullable Runnable getOnAccepted() {
            return myOnAccepted;
        }
    }

    /**
     * No usable response: the server is not running, responded with an error, or timed out.
     * The cache keeps its previous state and re-requests on the next {@link LspHighlightingCache#getHighlightings} call.
     */
    final class Failed<T> implements LspPullResult<T> {
        @SuppressWarnings("rawtypes")
        public static final Failed INSTANCE = new Failed();

        private Failed() {
        }
    }
}

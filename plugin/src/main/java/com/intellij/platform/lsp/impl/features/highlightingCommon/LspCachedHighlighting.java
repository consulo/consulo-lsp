package com.intellij.platform.lsp.impl.features.highlightingCommon;

import consulo.application.progress.ProgressManager;
import consulo.document.util.TextRange;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;

/**
 * Stores some {@link #getHighlightingInfo() highlighting-related information} received from an LSP server,
 * and also a {@link #getTextRange() text range} to which this information applies.
 */
public final class LspCachedHighlighting<T> {
    private final TextRange myTextRange;
    private final T myHighlightingInfo;

    /**
     * @param textRange        A text range that has some kind of highlighting-related data associated with it.
     * @param highlightingInfo Any data that describes some kind of highlighting related to the {@code textRange}.
     *                         For example, syntax highlighting, diagnostics, inlay hint-related information, etc.
     */
    public LspCachedHighlighting(TextRange textRange, T highlightingInfo) {
        myTextRange = textRange;
        myHighlightingInfo = highlightingInfo;
    }

    /**
     * A text range that has some kind of highlighting-related data associated with it.
     */
    public TextRange getTextRange() {
        return myTextRange;
    }

    /**
     * Any data that describes some kind of highlighting related to the {@link #getTextRange() textRange}.
     * For example, syntax highlighting, diagnostics, inlay hint-related information, etc.
     */
    public T getHighlightingInfo() {
        return myHighlightingInfo;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o
            || o instanceof LspCachedHighlighting<?> that
            && myTextRange.equals(that.myTextRange)
            && Objects.equals(myHighlightingInfo, that.myHighlightingInfo);
    }

    @Override
    public int hashCode() {
        return 31 * myTextRange.hashCode() + Objects.hashCode(myHighlightingInfo);
    }

    @Override
    public String toString() {
        return "LspCachedHighlighting(textRange=" + myTextRange + ", highlightingInfo=" + myHighlightingInfo + ")";
    }

    /**
     * Adjusts highlighting ranges after document edits.
     * <p>
     * Edits fully contained in a highlighting grow/shrink it; edits that only partially overlap a highlighting
     * remove it; edits entirely outside a highlighting shift it as needed. The next authoritative update from
     * the server is expected to replace the adjusted highlightings soon after.
     */
    @Contract(pure = true)
    public static <T> List<LspCachedHighlighting<T>> applyPendingEdits(
        List<LspCachedHighlighting<T>> highlightings,
        Collection<PendingEdit> edits
    ) {
        if (edits.isEmpty()) {
            return highlightings;
        }

        List<LspCachedHighlighting<T>> updatedHighlightings = new ArrayList<>(highlightings);
        for (PendingEdit edit : edits) {
            ProgressManager.checkCanceled();
            applyPendingEdit(updatedHighlightings, edit);
        }
        return updatedHighlightings;
    }

    private static <T> void applyPendingEdit(List<LspCachedHighlighting<T>> highlightings, PendingEdit edit) {
        ListIterator<LspCachedHighlighting<T>> iterator = highlightings.listIterator();
        while (iterator.hasNext()) {
            LspCachedHighlighting<T> highlighting = iterator.next();
            TextRange textRange = highlighting.getTextRange();
            int editOldEndOffset = edit.getOffset() + edit.getOldLength();

            if (edit.getOffset() >= textRange.getEndOffset()) {
                // edit range is after this highlighting.textRange ⇒ no need to update the range
                continue;
            }

            if (editOldEndOffset <= textRange.getStartOffset()) {
                // edit range is before this highlighting.textRange ⇒ need to shift the range
                TextRange newTextRange = textRange.shiftRight(edit.getNewLength() - edit.getOldLength());
                iterator.set(new LspCachedHighlighting<>(newTextRange, highlighting.getHighlightingInfo()));
                continue;
            }

            if (edit.getOffset() >= textRange.getStartOffset() && editOldEndOffset <= textRange.getEndOffset()) {
                // changes within the highlighting.textRange ⇒ update the range
                TextRange newTextRange = textRange.grown(edit.getNewLength() - edit.getOldLength());
                iterator.set(new LspCachedHighlighting<>(newTextRange, highlighting.getHighlightingInfo()));
                continue;
            }

            // changed range intersects with the highlighting.textRange ⇒ delete highlighting
            iterator.remove();
        }
    }
}

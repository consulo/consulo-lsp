package com.intellij.platform.lsp.impl.features.highlighting;

import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.document.Document;
import consulo.document.util.TextRange;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightKind;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TextRangeAndHighlightKind {
    private final TextRange myTextRange;
    private final @Nullable DocumentHighlightKind myKind;

    public TextRangeAndHighlightKind(TextRange textRange, @Nullable DocumentHighlightKind kind) {
        myTextRange = textRange;
        myKind = kind;
    }

    public TextRange getTextRange() {
        return myTextRange;
    }

    public @Nullable DocumentHighlightKind getKind() {
        return myKind;
    }

    public static List<TextRangeAndHighlightKind> fromDocumentHighlights(List<DocumentHighlight> highlights, Document document) {
        List<TextRangeAndHighlightKind> result = new ArrayList<>(highlights.size());
        for (DocumentHighlight highlight : highlights) {
            TextRange textRange = Lsp4jUtilKt.getRangeInDocument(document, highlight.getRange());
            if (textRange == null) {
                continue;
            }
            result.add(new TextRangeAndHighlightKind(textRange, highlight.getKind()));
        }
        return result;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o
            || o instanceof TextRangeAndHighlightKind that && myTextRange.equals(that.myTextRange) && myKind == that.myKind;
    }

    @Override
    public int hashCode() {
        return 31 * myTextRange.hashCode() + Objects.hashCode(myKind);
    }

    @Override
    public String toString() {
        return "TextRangeAndHighlightKind(textRange=" + myTextRange + ", kind=" + myKind + ")";
    }
}

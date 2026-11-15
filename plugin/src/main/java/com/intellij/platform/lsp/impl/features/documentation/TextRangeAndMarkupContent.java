package com.intellij.platform.lsp.impl.features.documentation;

import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.document.Document;
import consulo.document.util.TextRange;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public final class TextRangeAndMarkupContent {
    private final TextRange myTextRange;
    private final MarkupContent myMarkupContent;

    public TextRangeAndMarkupContent(TextRange textRange, MarkupContent markupContent) {
        myTextRange = textRange;
        myMarkupContent = markupContent;
    }

    public TextRange getTextRange() {
        return myTextRange;
    }

    public MarkupContent getMarkupContent() {
        return myMarkupContent;
    }

    public static @Nullable TextRangeAndMarkupContent fromHover(Hover hover, @Nullable Document document, int offset) {
        Range range = hover.getRange();
        TextRange textRange;
        if (document != null && range != null) {
            TextRange rangeInDocument = Lsp4jUtilKt.getRangeInDocument(document, range);
            textRange = rangeInDocument != null ? rangeInDocument : new TextRange(offset, offset);
        }
        else {
            textRange = new TextRange(offset, offset);
        }

        MarkupContent markupContent = hover.getContents().getRight();
        if (markupContent != null) {
            if (markupContent.getValue() == null || markupContent.getValue().isBlank()) {
                return null;
            }
            return new TextRangeAndMarkupContent(textRange, markupContent);
        }

        List<Either<String, MarkedString>> left = Objects.requireNonNull(hover.getContents().getLeft());
        StringBuilder markup = new StringBuilder();
        for (int i = 0; i < left.size(); i++) {
            if (i > 0) {
                markup.append("\n\n");
            }
            Either<String, MarkedString> markupOrMarkedString = left.get(i);
            if (markupOrMarkedString.isLeft()) {
                markup.append(markupOrMarkedString.getLeft());
            }
            else {
                MarkedString markedString = Objects.requireNonNull(markupOrMarkedString.getRight());
                String codeSnippet = markedString.getValue();
                String language = markedString.getLanguage();
                markup.append("```").append(language != null ? language : "").append("\n").append(codeSnippet).append("\n```");
            }
        }

        if (markup.toString().isBlank()) {
            return null;
        }
        return new TextRangeAndMarkupContent(textRange, new MarkupContent(MarkupKind.MARKDOWN, markup.toString()));
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TextRangeAndMarkupContent that)) {
            return false;
        }
        return myTextRange.equals(that.myTextRange) && myMarkupContent.equals(that.myMarkupContent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(myTextRange, myMarkupContent);
    }

    @Override
    public String toString() {
        return "TextRangeAndMarkupContent(textRange=" + myTextRange + ", markupContent=" + myMarkupContent + ")";
    }
}

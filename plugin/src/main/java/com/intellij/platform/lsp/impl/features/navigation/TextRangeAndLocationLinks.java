// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.navigation;

import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.document.Document;
import consulo.document.util.TextRange;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Range;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Response to a {@code textDocument/definition} or {@code textDocument/typeDefinition} request,
 * bundled with the range in the requested document that the links originate from.
 */
public final class TextRangeAndLocationLinks {
    private final TextRange myTextRange;
    private final List<LocationLink> myLocationLinks;

    public TextRangeAndLocationLinks(TextRange textRange, List<LocationLink> locationLinks) {
        myTextRange = textRange;
        myLocationLinks = locationLinks;
    }

    public TextRange getTextRange() {
        return myTextRange;
    }

    public List<LocationLink> getLocationLinks() {
        return myLocationLinks;
    }

    /**
     * {@link #getTextRange()} is the union of the origin ranges reported by the server,
     * or an empty range at {@code offset} when the server reported none.
     */
    public static TextRangeAndLocationLinks fromLocationLinks(
        List<LocationLink> locationLinks,
        @Nullable Document document,
        int offset
    ) {
        TextRange originRange = null;
        if (document != null) {
            for (LocationLink link : locationLinks) {
                Range range = link.getOriginSelectionRange();
                if (range == null) {
                    continue;
                }
                TextRange textRange = Lsp4jUtilKt.getRangeInDocument(document, range);
                if (textRange == null) {
                    continue;
                }
                originRange = originRange == null ? textRange : originRange.union(textRange);
            }
        }
        return new TextRangeAndLocationLinks(originRange != null ? originRange : new TextRange(offset, offset), locationLinks);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TextRangeAndLocationLinks that)) {
            return false;
        }
        return myTextRange.equals(that.myTextRange) && myLocationLinks.equals(that.myLocationLinks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(myTextRange, myLocationLinks);
    }

    @Override
    public String toString() {
        return "TextRangeAndLocationLinks(textRange=" + myTextRange + ", locationLinks=" + myLocationLinks + ")";
    }
}

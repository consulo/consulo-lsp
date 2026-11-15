// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl;

import org.eclipse.lsp4j.Range;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Range in an LSP document with document identifier.
 * <p>
 * For notebooks, the document may contain a cell URI and the range is cell-relative.
 */
public final class LspDocumentRange {
    private final LspDocument myDocument;
    private final Range myRange;

    public LspDocumentRange(LspDocument document, Range range) {
        myDocument = document;
        myRange = range;
    }

    public LspDocument getDocument() {
        return myDocument;
    }

    public Range getRange() {
        return myRange;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LspDocumentRange that)) {
            return false;
        }
        return myDocument.equals(that.myDocument) && myRange.equals(that.myRange);
    }

    @Override
    public int hashCode() {
        return Objects.hash(myDocument, myRange);
    }

    @Override
    public String toString() {
        return "LspDocumentRange(document=" + myDocument + ", range=" + myRange + ")";
    }
}

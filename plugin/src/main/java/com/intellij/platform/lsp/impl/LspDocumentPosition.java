// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl;

import org.eclipse.lsp4j.Position;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Position in an LSP document with document identifier.
 * <p>
 * For notebooks, the document may contain a cell URI (e.g., "file.ipynb#cell-0")
 * and the position is cell-relative.
 */
public final class LspDocumentPosition {
    private final LspDocument myDocument;
    private final Position myPosition;

    public LspDocumentPosition(LspDocument document, Position position) {
        myDocument = document;
        myPosition = position;
    }

    public LspDocument getDocument() {
        return myDocument;
    }

    public Position getPosition() {
        return myPosition;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LspDocumentPosition that)) {
            return false;
        }
        return myDocument.equals(that.myDocument) && myPosition.equals(that.myPosition);
    }

    @Override
    public int hashCode() {
        return Objects.hash(myDocument, myPosition);
    }

    @Override
    public String toString() {
        return "LspDocumentPosition(document=" + myDocument + ", position=" + myPosition + ")";
    }
}

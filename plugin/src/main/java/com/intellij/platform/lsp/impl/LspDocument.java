// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl;

import consulo.document.Document;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.jspecify.annotations.Nullable;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Models a single LSP {@code TextDocument} from the IDE side of the protocol.
 * <p>
 * For a regular file this is the file itself. For a notebook file there is one {@link LspDocument} per cell.
 * <p>
 * Coordinates carried by {@link #getId()} are in this document's own space (cell-local for a notebook cell).
 * The {@link #toHostPosition}/{@link #toHostRange}/{@link #toHostLine} helpers translate them back to the host file's
 * coordinate space so the rest of the platform sees positions in the file the user edits.
 */
public interface LspDocument {
    /**
     * The URI of the host {@link consulo.virtualFileSystem.VirtualFile} (without cell fragment).
     * For regular files, this is the file URI. For notebook cells, this is the notebook file URI.
     * Use {@link #getId()} for the LSP {@code TextDocument} identifier (which may include a cell fragment).
     */
    String getFileUri();

    /**
     * Identifier sent to the server in {@code textDocument/...} requests.
     */
    TextDocumentIdentifier getId();

    Position toHostPosition(Position position);

    Range toHostRange(Range range);

    int toHostLine(int line);

    /**
     * Translates {@code input} from this document's coordinate space to host-file space.
     * <p>
     * For identity documents (regular files) returns {@code input} as-is without invoking {@code transform}.
     * For documents that need translation (notebook cells) invokes {@code transform} with this document
     * as the first argument so it can call {@link #toHostRange}, {@link #toHostPosition}, {@link #toHostLine}, or nested mappers.
     */
    <T> T mapToHost(T input, BiFunction<LspDocument, T, T> transform);

    /**
     * Returns a {@link Document} whose coordinate space matches this LSP document, suitable for
     * translating LSP {@code (line, character)} positions to offsets and back.
     * <p>
     * Identity documents (regular files) may skip invoking {@code build} and return {@code hostDocument} directly.
     */
    default @Nullable Document prepareDocument(Document hostDocument, Function<Document, @Nullable Document> build) {
        return build.apply(hostDocument);
    }
}

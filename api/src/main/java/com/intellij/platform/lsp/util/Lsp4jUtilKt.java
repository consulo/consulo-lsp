// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.util;

import consulo.document.Document;
import consulo.document.DocumentWindow;
import consulo.document.util.TextRange;
import consulo.logging.Logger;
import consulo.util.lang.StringUtil;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SnippetTextEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class Lsp4jUtilKt {
    private static final Logger LOG = Logger.getInstance(Lsp4jUtilKt.class);

    private Lsp4jUtilKt() {
    }

    public static Position getLsp4jPosition(Document document, int offset) {
        if (document instanceof DocumentWindow documentWindow) {
            // It's very likely that the caller uses DocumentWindow not only when calling this function but also somewhere else.
            // The error helps to find the problematic place earlier.
            LOG.error("DocumentWindow is not expected here. Make sure to use DocumentWindow.delegate when working with LspClient.");
            return getLsp4jPosition(documentWindow.getDelegate(), documentWindow.injectedToHost(offset));
        }

        int lineNumber = document.getLineNumber(offset);
        return new Position(lineNumber, offset - document.getLineStartOffset(lineNumber));
    }

    public static Range getLsp4jRange(Document document, int offset, int length) {
        return new Range(getLsp4jPosition(document, offset), getLsp4jPosition(document, offset + length));
    }

    /**
     * Returns {@code null} if {@code position} is outside the document text range.
     */
    public static @Nullable Integer getOffsetInDocument(Document document, Position position) {
        if (document instanceof DocumentWindow documentWindow) {
            // It's very likely that the caller uses DocumentWindow not only when calling this function but also somewhere else.
            // The error helps to find the problematic place earlier.
            LOG.error("DocumentWindow is not expected here. Make sure to use DocumentWindow.delegate when working with the LSP server.");
            return getOffsetInDocument(documentWindow.getDelegate(), position);
        }

        int lineCount = document.getLineCount();
        int line = position.getLine();
        int character = position.getCharacter();
        if (line == lineCount && character == 0) {
            return document.getTextLength();
        }
        if (line < 0 || line >= lineCount || character < 0) {
            return null;
        }

        int lineStartOffset = document.getLineStartOffset(line);
        if (line + 1 < lineCount && character > 0) {
            // Make sure that the `character` value doesn't exceed the line length.
            // Some buggy servers may send range with `"character":80` for zero-length lines
            // Secondly, we use this code with textDocument/foldingRange, that by spec may omit character.
            // We intermittently use `Int.MAX_VALUE` and expect to clamp it here to the EOL.
            // See [com.intellij.platform.lsp.impl.features.folding.LspFoldingRangeCache.foldingRangeToLsp4jRange]
            int nextLineStartOffset = document.getLineStartOffset(line + 1);
            character = Math.min(character, nextLineStartOffset - 1 - lineStartOffset);
        }
        else if (line + 1 == lineCount && character > 0) {
            // workaround for servers that send { "line": <last_line>, "character": 2147483647 }
            character = Math.min(character, document.getTextLength() - lineStartOffset);
        }

        int offset = lineStartOffset + character;
        return offset <= document.getTextLength() ? offset : null;
    }

    /**
     * Returns {@code null} if {@code range} is partially or fully outside the document text range.
     */
    public static @Nullable TextRange getRangeInDocument(Document document, Range range) {
        Integer start = getOffsetInDocument(document, range.getStart());
        if (start == null) {
            return null;
        }
        Integer end = getOffsetInDocument(document, range.getEnd());
        if (end == null) {
            return null;
        }
        if (!TextRange.isProperRange(start, end)) {
            return null;
        }
        return new TextRange(start, end);
    }

    /**
     * Applies the {@code edits} of one {@code TextDocumentEdit}.
     * <p>
     * This function applies the {@link TextEdit} side only. It does not apply a {@link SnippetTextEdit},
     * a snippet needs an {@code Editor} to expand its placeholders, which this signature cannot supply.
     * <p>
     * By default, the IDE does not request {@code snippetEditSupport}, so a server must not send a {@link SnippetTextEdit}.
     * This function logs such an edit and returns {@code false}.
     *
     * @return {@code true} if all {@code edits} were applied successfully;
     * or {@code false} as soon as some edit failed to get applied to the {@code document}
     */
    public static boolean applySimpleTextEdits(Document document, List<Either<TextEdit, SnippetTextEdit>> edits) {
        List<TextEdit> textEdits = new ArrayList<>(edits.size());
        for (Either<TextEdit, SnippetTextEdit> edit : edits) {
            TextEdit textEdit = edit.getLeft();
            if (textEdit == null) {
                LOG.warn("Ignoring SnippetTextEdit, the IDE does not support it: " + edit.getRight());
                return false;
            }
            textEdits.add(textEdit);
        }
        return applyTextEdits(document, textEdits);
    }

    /**
     * @return {@code true} if all {@code textEdits} were applied successfully;
     * or {@code false} as soon as some {@code textEdit} failed to get applied to the {@code document}
     * because {@code textEdit.range} was outside the {@code document} text range
     */
    public static boolean applyTextEdits(Document document, List<TextEdit> textEdits) {
        // Spec:
        // > All text edits ranges refer to positions in the document they are computed on. Text edits ranges must never overlap.
        // > However, it is possible that multiple edits have the same start position: multiple inserts, ...
        // > If multiple inserts have the same position, the order in the array defines the order in which the inserted strings appear in the resulting text.
        //
        // The edits must be applied from bottom to top.
        // Edits that have the same position must be applied in the reversed order - this way the resulting text will get inserted strings in the original order.
        List<TextEdit> sorted = new ArrayList<>(textEdits);
        sorted.sort((edit1, edit2) -> {
            int lineDiff = edit1.getRange().getStart().getLine() - edit2.getRange().getStart().getLine();
            return lineDiff != 0 ? lineDiff : edit1.getRange().getStart().getCharacter() - edit2.getRange().getStart().getCharacter();
        });
        for (int i = sorted.size() - 1; i >= 0; i--) {
            if (!applyTextEdit(document, sorted.get(i))) {
                return false;
            }
        }

        return true;
    }

    /**
     * @return {@code true} if {@code textEdit} was applied successfully;
     * {@code false} if the {@code textEdit} can't be applied to the {@code document} because {@code textEdit.range} is outside the {@code document} text range
     */
    public static boolean applyTextEdit(Document document, TextEdit textEdit) {
        Integer startOffset = getOffsetInDocument(document, textEdit.getRange().getStart());
        Integer endOffset = getOffsetInDocument(document, textEdit.getRange().getEnd());
        if (startOffset == null || endOffset == null) {
            LOG.warn("Ignoring TextEdit, its text range is outside the document text range.\n" +
                "document.lineCount = " + document.getLineCount() + ", document.textLength = " + document.getTextLength() +
                ", range: " + textEdit.getRange());
            return false;
        }

        String newText = StringUtil.convertLineSeparators(textEdit.getNewText());
        document.replaceString(startOffset, endOffset, newText);
        return true;
    }

    /**
     * The message of the {@link Diagnostic}, when the server sent it as a string.
     * <p>
     * LSP 3.18 also permits a {@link MarkupContent} message, but only when the client sets the
     * {@code textDocument.diagnostic.markupMessageSupport} capability. The IDE does not set it, so a server must
     * send a string. This property gives an empty string for a markup message. It does not flatten the markup
     * to its raw value, because that loses the {@link MarkupContent#getKind()}.
     */
    public static String getMessageIfStringOrEmpty(Diagnostic diagnostic) {
        return diagnostic.getMessage().map(it -> it, it -> "");
    }
}

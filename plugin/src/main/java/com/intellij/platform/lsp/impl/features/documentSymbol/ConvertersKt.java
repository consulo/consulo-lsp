package com.intellij.platform.lsp.impl.features.documentSymbol;

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class ConvertersKt {
    private ConvertersKt() {
    }

    /**
     * Normalizes a {@code textDocument/documentSymbol} response to a {@link DocumentSymbol} tree.
     * <p>
     * lsp4j merges the two response shapes into one list type.
     * VSCode declares {@code DocumentSymbol[] | SymbolInformation[]},
     * but lsp4j declares {@code (DocumentSymbol | SymbolInformation)[]}.
     */
    public static List<DocumentSymbol> toDocumentSymbols(List<Either<SymbolInformation, DocumentSymbol>> symbols) {
        if (!symbols.isEmpty() && symbols.get(0).isLeft()) {
            List<SymbolInformation> infos = new ArrayList<>();
            for (Either<SymbolInformation, DocumentSymbol> symbol : symbols) {
                if (symbol.getLeft() != null) {
                    infos.add(symbol.getLeft());
                }
            }
            return toDocumentSymbolTree(infos);
        }
        List<DocumentSymbol> result = new ArrayList<>();
        for (Either<SymbolInformation, DocumentSymbol> symbol : symbols) {
            if (symbol.getRight() != null) {
                result.add(symbol.getRight());
            }
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    public static DocumentSymbol symbolInformationToDocumentSymbol(SymbolInformation info) {
        Range locationRange = info.getLocation().getRange();
        DocumentSymbol symbol = new DocumentSymbol();
        symbol.setName(info.getName());
        symbol.setKind(info.getKind());
        symbol.setRange(locationRange);
        symbol.setSelectionRange(locationRange);
        symbol.setDetail("");
        //containerName = info.containerName // VSCode removed that field
        symbol.setTags(info.getTags());
        symbol.setDeprecated(info.getDeprecated()); // VSCode removed that field and migrated it into tags
        symbol.setChildren(new ArrayList<>());
        return symbol;
    }

    // adapted from VSCode
    @SuppressWarnings("deprecation")
    public static List<DocumentSymbol> toDocumentSymbolTree(List<SymbolInformation> infos) {
        // Sort by start asc, then end desc
        List<SymbolInformation> sorted = new ArrayList<>(infos);
        sorted.sort((a, b) -> {
            int res = comparePositions(a.getLocation().getRange().getStart(), b.getLocation().getRange().getStart());
            if (res != 0) {
                return res;
            }
            return comparePositions(b.getLocation().getRange().getEnd(), a.getLocation().getRange().getEnd());
        });

        List<DocumentSymbol> result = new ArrayList<>();
        Deque<DocumentSymbol> parentStack = new ArrayDeque<>();

        for (SymbolInformation info : sorted) {
            DocumentSymbol element = symbolInformationToDocumentSymbol(info);

            while (true) {
                if (parentStack.isEmpty()) {
                    parentStack.addLast(element);
                    result.add(element);
                    break;
                }
                DocumentSymbol parent = parentStack.getLast();
                if (containsRange(parent.getRange(), element.getRange()) && !equalsRange(parent.getRange(), element.getRange())) {
                    parent.getChildren().add(element);
                    parentStack.addLast(element);
                    break;
                }
                parentStack.removeLast();
            }
        }

        return result;
    }

    public static int comparePositions(Position a, Position b) {
        int lineDiff = a.getLine() - b.getLine();
        return lineDiff != 0 ? lineDiff : a.getCharacter() - b.getCharacter();
    }

    public static boolean containsPosition(Range range, Position position) {
        return comparePositions(range.getStart(), position) <= 0 &&
            comparePositions(position, range.getEnd()) <= 0;
    }

    private static boolean containsRange(Range outer, Range inner) {
        return comparePositions(outer.getStart(), inner.getStart()) <= 0 &&
            comparePositions(outer.getEnd(), inner.getEnd()) >= 0;
    }

    private static boolean equalsRange(Range a, Range b) {
        return comparePositions(a.getStart(), b.getStart()) == 0 &&
            comparePositions(a.getEnd(), b.getEnd()) == 0;
    }
}

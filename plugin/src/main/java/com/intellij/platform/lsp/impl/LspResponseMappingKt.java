// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl;

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.SelectionRange;
import org.eclipse.lsp4j.TextEdit;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class LspResponseMappingKt {
    private LspResponseMappingKt() {
    }

    public static Location mapLocation(LspDocument document, Location location) {
        return document.mapToHost(location, (doc, it) -> new Location(doc.getFileUri(), doc.toHostRange(it.getRange())));
    }

    public static LocationLink mapLocationLink(LspDocument document, LocationLink locationLink) {
        return document.mapToHost(locationLink, (doc, it) -> new LocationLink(
            doc.getFileUri(),
            doc.toHostRange(it.getTargetRange()),
            doc.toHostRange(it.getTargetSelectionRange()),
            // originSelectionRange is already in host coordinates (sent by the IDE side), no mapping needed
            it.getOriginSelectionRange()
        ));
    }

    public static DocumentSymbol mapDocumentSymbol(LspDocument document, DocumentSymbol documentSymbol) {
        return document.mapToHost(documentSymbol, (doc, it) -> {
            @Nullable List<DocumentSymbol> children = null;
            if (it.getChildren() != null) {
                children = new ArrayList<>();
                for (DocumentSymbol child : it.getChildren()) {
                    children.add(mapDocumentSymbol(doc, child));
                }
            }
            return new DocumentSymbol(
                it.getName(),
                it.getKind(),
                doc.toHostRange(it.getRange()),
                doc.toHostRange(it.getSelectionRange()),
                it.getDetail(),
                children
            );
        });
    }

    public static TextEdit mapTextEdit(LspDocument document, TextEdit edit) {
        return document.mapToHost(edit, (doc, it) -> new TextEdit(doc.toHostRange(it.getRange()), it.getNewText()));
    }

    public static FoldingRange mapFoldingRange(LspDocument document, FoldingRange foldingRange) {
        return document.mapToHost(foldingRange, (doc, it) -> {
            FoldingRange mapped = new FoldingRange(doc.toHostLine(it.getStartLine()), doc.toHostLine(it.getEndLine()));
            mapped.setKind(it.getKind());
            mapped.setCollapsedText(it.getCollapsedText());
            mapped.setStartCharacter(it.getStartCharacter());
            mapped.setEndCharacter(it.getEndCharacter());
            return mapped;
        });
    }

    public static SelectionRange mapSelectionRange(LspDocument document, SelectionRange selectionRange) {
        return document.mapToHost(selectionRange, (doc, it) -> {
            SelectionRange result = new SelectionRange();
            result.setRange(doc.toHostRange(it.getRange()));
            result.setParent(it.getParent() != null ? mapSelectionRange(doc, it.getParent()) : null);
            return result;
        });
    }
}

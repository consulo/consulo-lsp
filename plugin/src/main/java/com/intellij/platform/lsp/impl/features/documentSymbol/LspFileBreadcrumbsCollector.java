// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.documentSymbol;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.document.Document;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.DocumentSymbol;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Document symbol helpers used by the navigation bar ({@link LspNavBarModelExtension}). There is no editor breadcrumbs
 * extension point (and sticky lines come only from {@code PsiStructureViewFactory}), so no breadcrumbs collector is registered.
 */
public final class LspFileBreadcrumbsCollector {
    private LspFileBreadcrumbsCollector() {
    }

    /**
     * Finds the client that provides breadcrumbs and navigation bar members for {@code file}.
     */
    public static @Nullable LspClientImpl findBreadcrumbsLspClient(Project project, VirtualFile file) {
        for (LspClientImpl client : LspClientManagerImpl.getInstanceImpl(project).getClientsWithThisFileOpen(file)) {
            if (client.getDescriptor().getLspCustomization().getDocumentSymbolCustomizer().getBreadcrumbsSupport() &&
                LspServerCapabilitiesKt.supportsDocumentSymbol(client, file)) {
                return client;
            }
        }
        return null;
    }

    /**
     * Returns the symbols that contain {@code offset}, from the outermost to the innermost.
     */
    public static List<DocumentSymbol> findSymbolsAroundOffset(Document document, List<DocumentSymbol> documentSymbols, int offset) {
        List<DocumentSymbol> currentLevelSymbols = documentSymbols;
        List<DocumentSymbol> relevantSymbols = new ArrayList<>();

        while (currentLevelSymbols != null) {
            DocumentSymbol child = null;
            for (DocumentSymbol documentSymbol : currentLevelSymbols) {
                Integer startOffset = Lsp4jUtilKt.getOffsetInDocument(document, documentSymbol.getRange().getStart());
                Integer endOffset = Lsp4jUtilKt.getOffsetInDocument(document, documentSymbol.getRange().getEnd());
                if (startOffset != null && offset >= startOffset && endOffset != null && offset <= endOffset) {
                    child = documentSymbol;
                    break;
                }
            }
            if (child != null) {
                relevantSymbols.add(child);
                currentLevelSymbols = child.getChildren();
            }
            else {
                currentLevelSymbols = null;
            }
        }
        return relevantSymbols;
    }
}

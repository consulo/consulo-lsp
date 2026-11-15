// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.documentSymbol;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.impl.features.navigation.LspNavigationUtilKt;
import consulo.project.Project;
import consulo.ui.image.Image;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.jspecify.annotations.Nullable;

import java.util.List;

public class LspStructureViewSupport {
    private final LspClientImpl myLspClient;
    private final VirtualFile myFile;

    private LspStructureViewSupport(LspClientImpl lspClient, VirtualFile file) {
        myLspClient = lspClient;
        myFile = file;
    }

    public List<DocumentSymbol> getDocumentSymbols() {
        List<DocumentSymbol> symbols = myLspClient.getRequestExecutor().getDocumentSymbolsCaching(myFile);
        return symbols != null ? symbols : List.of();
    }

    public @Nullable Image getIcon(DocumentSymbol symbol) {
        return myLspClient.getDescriptor().getLspCustomization().getSymbolKindCustomizer().getIcon(symbol.getKind());
    }

    public void navigate(Position position, boolean requestFocus) {
        LspNavigationUtilKt.navigateToLspPosition(myFile, myLspClient.getProject(), position, requestFocus);
    }

    public static @Nullable LspStructureViewSupport find(Project project, VirtualFile file) {
        for (LspClientImpl client : LspClientManagerImpl.getInstanceImpl(project).getClientsWithThisFileOpen(file)) {
            if (client.getDescriptor().getLspCustomization().getDocumentSymbolCustomizer().getStructureViewSupport() &&
                LspServerCapabilitiesKt.supportsDocumentSymbol(client, file)) {
                return new LspStructureViewSupport(client, file);
            }
        }
        return null;
    }
}

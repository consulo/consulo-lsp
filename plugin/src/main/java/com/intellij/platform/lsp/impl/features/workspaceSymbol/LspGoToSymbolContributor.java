package com.intellij.platform.lsp.impl.features.workspaceSymbol;

import consulo.annotation.component.ExtensionImpl;
import consulo.ide.navigation.GotoSymbolContributor;
import org.eclipse.lsp4j.SymbolKind;

@ExtensionImpl
public class LspGoToSymbolContributor extends LspWorkspaceSymbolContributor implements GotoSymbolContributor {
    @Override
    protected boolean shouldAcceptSymbolKind(SymbolKind symbolKind) {
        return true;
    }
}

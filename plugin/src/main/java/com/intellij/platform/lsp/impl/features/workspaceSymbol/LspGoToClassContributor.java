package com.intellij.platform.lsp.impl.features.workspaceSymbol;

import consulo.annotation.component.ExtensionImpl;
import consulo.ide.navigation.GotoClassOrTypeContributor;
import org.eclipse.lsp4j.SymbolKind;

/**
 * Consulo: upstream {@code gotoClassContributor} is {@link GotoClassOrTypeContributor}.
 */
@ExtensionImpl
public class LspGoToClassContributor extends LspWorkspaceSymbolContributor implements GotoClassOrTypeContributor {
    @Override
    protected boolean shouldAcceptSymbolKind(SymbolKind symbolKind) {
        switch (symbolKind) {
            case Class:
            case Interface:
            case Enum:
            case Struct:
                return true;
            default:
                return false;
        }
    }
}

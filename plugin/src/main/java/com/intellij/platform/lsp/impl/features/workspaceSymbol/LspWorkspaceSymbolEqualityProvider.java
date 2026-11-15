// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.workspaceSymbol;

import com.intellij.platform.lsp.api.customization.LspWorkspaceSymbolNavigationItem;
import consulo.annotation.component.ExtensionImpl;
import consulo.searchEverywhere.SEResultsEqualityProvider;
import consulo.searchEverywhere.SearchEverywhereFoundElementInfo;

/**
 * Equality provider for LSP workspace symbols to handle duplicate filtering in Search Everywhere.
 * <p>
 * This provider unwraps presentation wrappers and compares the actual LspWorkspaceSymbolNavigationItem
 * <p>
 * {@link #compareItems} handles a single already found item; items are compared by priority (as
 * {@code TrivialElementsEqualityProvider} does).
 */
@ExtensionImpl
public class LspWorkspaceSymbolEqualityProvider implements SEResultsEqualityProvider {
    @Override
    public SEEqualElementsActionType compareItems(
        SearchEverywhereFoundElementInfo newItem,
        SearchEverywhereFoundElementInfo alreadyFoundItem
    ) {
        if (areEqual(newItem, alreadyFoundItem)) {
            return newItem.getPriority() > alreadyFoundItem.getPriority()
                ? SEEqualElementsActionType.REPLACE
                : SEEqualElementsActionType.SKIP;
        }
        return SEEqualElementsActionType.DO_NOTHING;
    }

    protected boolean areEqual(SearchEverywhereFoundElementInfo newItem, SearchEverywhereFoundElementInfo alreadyFoundItem) {
        Object newElement = newItem.getElement();
        Object oldElement = alreadyFoundItem.getElement();

        // the found elements are LspWorkspaceSymbolPsiElement wrappers
        if (newElement instanceof LspWorkspaceSymbolPsiElement newWrapper) {
            newElement = newWrapper.getItem();
        }
        if (oldElement instanceof LspWorkspaceSymbolPsiElement oldWrapper) {
            oldElement = oldWrapper.getItem();
        }

        // Compare only if both are LSP workspace symbol navigation items
        return newElement instanceof LspWorkspaceSymbolNavigationItem &&
            oldElement instanceof LspWorkspaceSymbolNavigationItem &&
            newElement.equals(oldElement);
    }
}

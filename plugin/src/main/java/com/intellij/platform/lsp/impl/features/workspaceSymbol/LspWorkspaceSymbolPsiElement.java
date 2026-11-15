/*
 * Copyright 2013-2026 consulo.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.platform.lsp.impl.features.workspaceSymbol;

import consulo.annotation.access.RequiredReadAction;
import consulo.language.impl.psi.FakePsiElement;
import consulo.language.psi.PsiElement;
import consulo.localize.LocalizeValue;
import consulo.navigation.ItemPresentation;
import consulo.navigation.NavigationItem;
import consulo.ui.image.Image;
import org.jspecify.annotations.Nullable;

/**
 * Search Everywhere renders the Go To Symbol / Go To Class results with a {@code PsiElementListCellRenderer},
 * which needs {@link PsiElement}s. Wraps an LSP workspace symbol
 * navigation item into a non-physical element, which delegates the presentation and the navigation to the item.
 */
public final class LspWorkspaceSymbolPsiElement extends FakePsiElement implements NavigationItem {
    private final NavigationItem myItem;
    private final PsiElement myParent;

    public LspWorkspaceSymbolPsiElement(NavigationItem item, PsiElement parent) {
        myItem = item;
        myParent = parent;
    }

    public NavigationItem getItem() {
        return myItem;
    }

    @Override
    public PsiElement getParent() {
        return myParent;
    }

    @Override
    public @Nullable String getName() {
        return myItem.getName();
    }

    @Override
    public LocalizeValue getPresentableText() {
        ItemPresentation presentation = myItem.getPresentation();
        return presentation != null ? presentation.getPresentableText() : LocalizeValue.of(myItem.getName() != null ? myItem.getName() : "");
    }

    @Override
    public @Nullable String getLocationString() {
        ItemPresentation presentation = myItem.getPresentation();
        return presentation != null ? presentation.getLocationString() : null;
    }

    @Override
    public @Nullable Image getIcon() {
        ItemPresentation presentation = myItem.getPresentation();
        return presentation != null ? presentation.getIcon() : null;
    }

    @Override
    @RequiredReadAction
    public void navigate(boolean requestFocus) {
        myItem.navigate(requestFocus);
    }

    @Override
    public boolean canNavigate() {
        return myItem.canNavigate();
    }

    @Override
    public boolean canNavigateToSource() {
        return myItem.canNavigateToSource();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof LspWorkspaceSymbolPsiElement other && myItem.equals(other.myItem);
    }

    @Override
    public int hashCode() {
        return myItem.hashCode();
    }
}

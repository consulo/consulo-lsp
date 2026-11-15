package com.intellij.platform.lsp.impl.features.hierarchy;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.features.navigation.LspNavigationUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.language.editor.hierarchy.HierarchyNodeDescriptor;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.navigation.Navigatable;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.util.CompositeAppearance;
import consulo.ui.image.Image;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.SymbolKind;
import org.jspecify.annotations.Nullable;

public class LspHierarchyNodeDescriptor extends HierarchyNodeDescriptor implements Navigatable {
    private final LspClientImpl myClient;
    private final @Nullable Lsp4jHierarchyItem myItem;

    public LspHierarchyNodeDescriptor(
        LspClientImpl client,
        @Nullable Lsp4jHierarchyItem item,
        PsiElement element,
        @Nullable HierarchyNodeDescriptor parent,
        boolean isBase
    ) {
        super(client.getProject(), parent, element, isBase);
        myClient = client;
        myItem = item;
    }

    public LspClientImpl getClient() {
        return myClient;
    }

    public @Nullable Lsp4jHierarchyItem getItem() {
        return myItem;
    }

    public boolean isBase() {
        return myIsBase;
    }

    @Override
    @RequiredUIAccess
    public boolean update() {
        super.update();
        // the base update() does not take the icon from the overridable getIcon(element)
        PsiElement element = getPsiElement();
        setIcon(element != null ? getIcon(element) : null);
        if (myItem == null) {
            // the hierarchy tree root is always visible,
            // so the base node shows the file name instead of an empty row
            if (myHighlightedText.getText().isEmpty()) {
                PsiFile psiFile = getContainingFile();
                if (psiFile != null) {
                    myHighlightedText.getEnding().addText(psiFile.getName());
                    myName = myHighlightedText.getText();
                    return true;
                }
            }
            return false;
        }
        CompositeAppearance oldText = myHighlightedText;
        myHighlightedText = new CompositeAppearance();

        myHighlightedText.getEnding().addText(myItem.getName());
        String detail = myItem.getDetail();
        if (detail != null && !detail.isBlank()) {
            myHighlightedText.getEnding().addText(" : " + detail, getPackageNameAttributes());
        }
        myName = myHighlightedText.getText();
        return !myHighlightedText.equals(oldText);
    }

    @Override
    @RequiredReadAction
    public boolean canNavigate() {
        if (myItem == null) {
            return false;
        }
        return !myItem.getUri().isEmpty();
    }

    @Override
    @RequiredReadAction
    public void navigate(boolean requestFocus) {
        if (myItem == null) {
            return;
        }
        VirtualFile targetFile = myClient.getDescriptor().findFileByUri(myItem.getUri());
        if (targetFile == null) {
            return;
        }
        LspNavigationUtilKt.navigateToLspPosition(targetFile, getProject(), myItem.getSelectionRange().getStart(), requestFocus);
    }

    protected @Nullable Image getIcon(PsiElement element) {
        if (myItem == null) {
            return null;
        }
        SymbolKind kind = myItem.getKind();
        return myClient.getDescriptor().getLspCustomization().getSymbolKindCustomizer().getIcon(kind);
    }
}

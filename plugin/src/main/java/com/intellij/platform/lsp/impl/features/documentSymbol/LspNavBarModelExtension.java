// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.documentSymbol;

import com.intellij.platform.lsp.api.customization.LspSymbolKindCustomizer;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.ui.UISettings;
import consulo.application.util.registry.Registry;
import consulo.codeEditor.Editor;
import consulo.dataContext.DataContext;
import consulo.document.Document;
import consulo.document.util.TextRange;
import consulo.language.impl.psi.FakePsiElement;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.ui.navigationBar.NavBarModelExtension;
import consulo.navigation.OpenFileDescriptorFactory;
import consulo.ui.image.Image;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.DocumentSymbol;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Shows the {@code textDocument/documentSymbol} symbols around the caret as members in the navigation bar.
 * The extension works when "Show members in navigation bar" is on and the client has
 * {@link com.intellij.platform.lsp.api.customization.LspDocumentSymbolCustomizer#getBreadcrumbsSupport() breadcrumbsSupport}.
 */
@ExtensionImpl
public class LspNavBarModelExtension implements NavBarModelExtension {
    @Override
    public @Nullable String getPresentableText(Object object) {
        return object instanceof LspDocumentSymbolPsiElement element ? element.getName() : null;
    }

    @Override
    public @Nullable Image getIcon(Object object) {
        return object instanceof LspDocumentSymbolPsiElement element ? element.getIcon() : null;
    }

    @Override
    public @Nullable PsiElement getParent(PsiElement psiElement) {
        return psiElement instanceof LspDocumentSymbolPsiElement element ? element.getParent() : null;
    }

    @Override
    @RequiredReadAction
    public @Nullable PsiElement getLeafElement(DataContext dataProvider) {
        if (!UISettings.getInstance().getShowMembersInNavigationBar()) {
            return null;
        }
        PsiFile psiFile = dataProvider.getData(PsiFile.KEY);
        if (psiFile == null) {
            return null;
        }
        Editor editor = dataProvider.getData(Editor.KEY);
        if (editor == null) {
            return null;
        }
        VirtualFile file = psiFile.getViewProvider().getVirtualFile();
        LspClientImpl lspClient = LspFileBreadcrumbsCollector.findBreadcrumbsLspClient(psiFile.getProject(), file);
        if (lspClient == null) {
            return null;
        }
        List<DocumentSymbol> documentSymbols = lspClient.getRequestExecutor().getDocumentSymbolsCaching(file);
        if (documentSymbols == null) {
            return null;
        }

        Document document = editor.getDocument();
        LspSymbolKindCustomizer customizer = lspClient.getDescriptor().getLspCustomization().getSymbolKindCustomizer();
        LspDocumentSymbolPsiElement leaf = null;
        for (DocumentSymbol symbol : LspFileBreadcrumbsCollector.findSymbolsAroundOffset(document, documentSymbols, editor.getCaretModel().getOffset())) {
            LspDocumentSymbolPsiElement next = LspDocumentSymbolPsiElement.create(customizer, psiFile, document, leaf != null ? leaf : psiFile, symbol);
            if (next == null) {
                break;
            }
            leaf = next;
        }
        return leaf;
    }

    @Override
    public boolean processChildren(Object object, Object rootElement, Predicate<Object> processor) {
        List<LspDocumentSymbolPsiElement> children;
        if (object instanceof LspDocumentSymbolPsiElement element) {
            children = element.children();
        }
        else if (object instanceof PsiFile psiFile) {
            children = topLevelSymbols(psiFile);
        }
        else {
            return true;
        }
        for (LspDocumentSymbolPsiElement child : children) {
            if (!processor.test(child)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean normalizeChildren() {
        return false;
    }

    private List<LspDocumentSymbolPsiElement> topLevelSymbols(PsiFile psiFile) {
        if (!UISettings.getInstance().getShowMembersInNavigationBar()) {
            return List.of();
        }
        VirtualFile file = psiFile.getViewProvider().getVirtualFile();
        LspClientImpl lspClient = LspFileBreadcrumbsCollector.findBreadcrumbsLspClient(psiFile.getProject(), file);
        if (lspClient == null) {
            return List.of();
        }
        List<DocumentSymbol> documentSymbols = lspClient.getRequestExecutor().getDocumentSymbolsCaching(file);
        if (documentSymbols == null) {
            return List.of();
        }
        Document document = psiFile.getViewProvider().getDocument();
        if (document == null) {
            return List.of();
        }
        LspSymbolKindCustomizer customizer = lspClient.getDescriptor().getLspCustomization().getSymbolKindCustomizer();
        List<LspDocumentSymbolPsiElement> result = new ArrayList<>();
        for (DocumentSymbol symbol : documentSymbols) {
            LspDocumentSymbolPsiElement element = LspDocumentSymbolPsiElement.create(customizer, psiFile, document, psiFile, symbol);
            if (element != null) {
                result.add(element);
            }
        }
        return result;
    }

    /**
     * Returns {@code true} when the navigation bar shows the document symbols as members.
     * Then the editor does not show the LSP breadcrumbs bar.
     * Set the {@code lsp.navbar.members.hide.editor.breadcrumbs} registry key to {@code false} to always show the bar.
     */
    public static boolean isEditorBreadcrumbsReplacedByNavBar() {
        UISettings settings = UISettings.getInstance();
        return settings.getShowNavigationBar() &&
            settings.getShowMembersInNavigationBar() &&
            Registry.is("lsp.navbar.members.hide.editor.breadcrumbs", true);
    }

    /**
     * A {@link DocumentSymbol} in the navigation bar.
     * The {@code parent} is the enclosing symbol, or the {@code psiFile} for a top-level symbol.
     */
    private static final class LspDocumentSymbolPsiElement extends FakePsiElement {
        private final LspSymbolKindCustomizer myCustomizer;
        private final PsiFile myPsiFile;
        private final Document myDocument;
        private final PsiElement myParent;
        private final DocumentSymbol mySymbol;
        private final TextRange myRange;
        private final int mySelectionOffset;

        private LspDocumentSymbolPsiElement(
            LspSymbolKindCustomizer customizer,
            PsiFile psiFile,
            Document document,
            PsiElement parent,
            DocumentSymbol symbol,
            TextRange range,
            int selectionOffset
        ) {
            myCustomizer = customizer;
            myPsiFile = psiFile;
            myDocument = document;
            myParent = parent;
            mySymbol = symbol;
            myRange = range;
            mySelectionOffset = selectionOffset;
        }

        @Override
        public PsiElement getParent() {
            return myParent;
        }

        @Override
        public PsiFile getContainingFile() {
            return myPsiFile;
        }

        @Override
        public String getName() {
            return mySymbol.getName();
        }

        @Override
        public @Nullable Image getIcon() {
            return myCustomizer.getIcon(mySymbol.getKind());
        }

        @Override
        public TextRange getTextRange() {
            return myRange;
        }

        @Override
        public int getTextOffset() {
            return mySelectionOffset;
        }

        @Override
        public boolean isValid() {
            return myPsiFile.isValid();
        }

        /**
         * Consulo: upstream {@code navigationRequest()}; there is no {@code NavigationRequests} in Consulo.
         */
        @Override
        public void navigate(boolean requestFocus) {
            OpenFileDescriptorFactory.getInstance(myPsiFile.getProject())
                .newBuilder(myPsiFile.getViewProvider().getVirtualFile())
                .offset(mySelectionOffset)
                .build()
                .navigate(requestFocus);
        }

        @Override
        public boolean canNavigate() {
            return myPsiFile.isValid();
        }

        @Override
        public boolean canNavigateToSource() {
            return canNavigate();
        }

        List<LspDocumentSymbolPsiElement> children() {
            List<DocumentSymbol> children = mySymbol.getChildren();
            if (children == null) {
                return List.of();
            }
            List<LspDocumentSymbolPsiElement> result = new ArrayList<>();
            for (DocumentSymbol child : children) {
                LspDocumentSymbolPsiElement element = create(myCustomizer, myPsiFile, myDocument, this, child);
                if (element != null) {
                    result.add(element);
                }
            }
            return result;
        }

        /**
         * Returns {@code null} when the {@code symbol} range is outside the {@code document}.
         */
        static @Nullable LspDocumentSymbolPsiElement create(
            LspSymbolKindCustomizer customizer,
            PsiFile psiFile,
            Document document,
            PsiElement parent,
            DocumentSymbol symbol
        ) {
            TextRange range = Lsp4jUtilKt.getRangeInDocument(document, symbol.getRange());
            if (range == null) {
                return null;
            }
            Integer selectionOffset = Lsp4jUtilKt.getOffsetInDocument(document, symbol.getSelectionRange().getStart());
            return new LspDocumentSymbolPsiElement(
                customizer, psiFile, document, parent, symbol, range, selectionOffset != null ? selectionOffset : range.getStartOffset()
            );
        }
    }
}

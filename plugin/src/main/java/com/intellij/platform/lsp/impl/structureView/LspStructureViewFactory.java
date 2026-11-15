// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.structureView;

import com.intellij.platform.lsp.impl.features.documentSymbol.LspStructureViewSupport;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.codeEditor.CodeInsightColors;
import consulo.codeEditor.Editor;
import consulo.colorScheme.TextAttributesKey;
import consulo.document.Document;
import consulo.fileEditor.structureView.StructureViewBuilder;
import consulo.fileEditor.structureView.StructureViewBuilderProvider;
import consulo.fileEditor.structureView.StructureViewModel;
import consulo.fileEditor.structureView.StructureViewTreeElement;
import consulo.fileEditor.structureView.TreeBasedStructureViewBuilder;
import consulo.language.editor.structureView.TextEditorBasedStructureViewModel;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.localize.LocalizeValue;
import consulo.navigation.ItemPresentation;
import consulo.project.Project;
import consulo.ui.ex.ColoredItemPresentation;
import consulo.ui.image.Image;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.fileType.FileType;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.SymbolTag;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Consulo: upstream is a {@code lang.psiStructureViewFactory} registered for {@code language=""}, which IntelliJ consults for any
 * language without a factory of its own. Consulo's {@code PsiStructureViewFactory} has no such fallback (an extension for
 * {@code Language.ANY} is not used for other languages), so the factory is registered as a {@link StructureViewBuilderProvider},
 * ordered after the platform provider that asks the language PsiStructureViewFactory: the language-specific structure view (via {@code PsiStructureViewFactory}) still wins, and the files of any other
 * language or file type (plain text included) get the LSP structure view.
 */
@ExtensionImpl(order = "after LanguageFileTypeStructureViewBuilderProvider")
public class LspStructureViewFactory implements StructureViewBuilderProvider {
    @Override
    @RequiredReadAction
    public @Nullable StructureViewBuilder getStructureViewBuilder(FileType fileType, VirtualFile file, Project project) {
        if (project.isDefault()) {
            return null;
        }
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile == null) {
            return null;
        }
        return getStructureViewBuilder(psiFile);
    }

    public @Nullable StructureViewBuilder getStructureViewBuilder(PsiFile psiFile) {
        VirtualFile file = psiFile.getVirtualFile();
        if (file == null || file instanceof VirtualFileWindow) {
            return null;
        }

        LspStructureViewSupport support = LspStructureViewSupport.find(psiFile.getProject(), file);
        if (support == null) {
            return null;
        }

        return new TreeBasedStructureViewBuilder() {
            @Override
            public StructureViewModel createStructureViewModel(@Nullable Editor editor) {
                return new LspStructureViewModel(support, psiFile, editor);
            }

            @Override
            public boolean isRootNodeShown() {
                return false;
            }
        };
    }

    private static final class LspStructureViewModel extends TextEditorBasedStructureViewModel implements StructureViewModel.ElementInfoProvider {
        private final LspStructureViewSupport mySupport;
        private LspStructureViewRoot myRoot;
        private long myLastPsiModificationCount;

        @SuppressWarnings("DataFlowIssue")
        private LspStructureViewModel(LspStructureViewSupport support, PsiFile psiFile, @Nullable Editor editor) {
            super(editor, psiFile);
            mySupport = support;
            myLastPsiModificationCount = psiFile.getManager().getModificationTracker().getModificationCount();
            myRoot = initRoot();
        }

        /**
         * Consulo: there is no {@code StructureViewModel.isValid()} (upstream invalidates the model on a PSI change);
         * the root is recomputed instead when the PSI changed.
         */
        @Override
        public StructureViewTreeElement getRoot() {
            long modificationCount = getPsiFile().getManager().getModificationTracker().getModificationCount();
            if (modificationCount != myLastPsiModificationCount) {
                myLastPsiModificationCount = modificationCount;
                myRoot = initRoot();
            }
            return myRoot;
        }

        private LspStructureViewRoot initRoot() {
            return new LspStructureViewRoot(mySupport, getPsiFile(), mySupport.getDocumentSymbols());
        }

        @Override
        public boolean isAlwaysShowsPlus(StructureViewTreeElement element) {
            return false;
        }

        @Override
        public boolean isAlwaysLeaf(StructureViewTreeElement element) {
            return false;
        }

        @Override
        public @Nullable Object getCurrentEditorElement() {
            Editor editor = getEditor();
            if (editor == null) {
                return null;
            }
            PsiFile psiFile = getPsiFile();
            if (!psiFile.isValid()) {
                return null;
            }
            LspDocumentSymbolTreeElement element = findSymbolsAroundOffset(psiFile.getFileDocument(), myRoot.getChildren(), editor.getCaretModel().getOffset());
            return element != null ? element.getValue() : null;
        }

        private @Nullable LspDocumentSymbolTreeElement findSymbolsAroundOffset(
            Document document,
            LspDocumentSymbolTreeElement[] documentSymbols,
            int offset
        ) {
            LspDocumentSymbolTreeElement[] currentLevelSymbols = documentSymbols;
            LspDocumentSymbolTreeElement relevantSymbol = null;

            while (currentLevelSymbols != null) {
                LspDocumentSymbolTreeElement child = null;
                for (LspDocumentSymbolTreeElement documentSymbol : currentLevelSymbols) {
                    Integer startOffset = Lsp4jUtilKt.getOffsetInDocument(document, documentSymbol.getValue().getRange().getStart());
                    Integer endOffset = Lsp4jUtilKt.getOffsetInDocument(document, documentSymbol.getValue().getRange().getEnd());
                    if (startOffset != null && offset >= startOffset && endOffset != null && offset <= endOffset) {
                        child = documentSymbol;
                        break;
                    }
                }
                if (child != null) {
                    relevantSymbol = child;
                    currentLevelSymbols = child.getChildren();
                }
                else {
                    currentLevelSymbols = null;
                }
            }
            return relevantSymbol;
        }
    }

    private static final class LspStructureViewRoot implements StructureViewTreeElement {
        private final LspStructureViewSupport mySupport;
        private final PsiFile myPsiFile;
        private final List<DocumentSymbol> myDocumentSymbols;

        private LspStructureViewRoot(LspStructureViewSupport support, PsiFile psiFile, List<DocumentSymbol> documentSymbols) {
            mySupport = support;
            myPsiFile = psiFile;
            myDocumentSymbols = documentSymbols;
        }

        @Override
        public PsiFile getValue() {
            return myPsiFile;
        }

        @Override
        public LspDocumentSymbolTreeElement[] getChildren() {
            LspDocumentSymbolTreeElement[] children = new LspDocumentSymbolTreeElement[myDocumentSymbols.size()];
            for (int i = 0; i < children.length; i++) {
                children[i] = new LspDocumentSymbolTreeElement(mySupport, myDocumentSymbols.get(i));
            }
            return children;
        }

        @Override
        public ItemPresentation getPresentation() {
            ItemPresentation presentation = myPsiFile.getPresentation();
            if (presentation != null) {
                return presentation;
            }
            return new ItemPresentation() {
                @Override
                public LocalizeValue getPresentableText() {
                    return LocalizeValue.of(myPsiFile.getName());
                }

                @Override
                public @Nullable String getLocationString() {
                    return null;
                }

                @Override
                public @Nullable Image getIcon() {
                    return null;
                }
            };
        }

        @Override
        public void navigate(boolean requestFocus) {
            myPsiFile.navigate(requestFocus);
        }

        @Override
        public boolean canNavigate() {
            return myPsiFile.canNavigate();
        }

        @Override
        public boolean canNavigateToSource() {
            return myPsiFile.canNavigateToSource();
        }
    }

    private static final class LspDocumentSymbolTreeElement implements StructureViewTreeElement {
        private final LspStructureViewSupport mySupport;
        private final DocumentSymbol mySymbol;

        private LspDocumentSymbolTreeElement(LspStructureViewSupport support, DocumentSymbol symbol) {
            mySupport = support;
            mySymbol = symbol;
        }

        @Override
        public DocumentSymbol getValue() {
            return mySymbol;
        }

        @Override
        public LspDocumentSymbolTreeElement[] getChildren() {
            List<DocumentSymbol> children = mySymbol.getChildren();
            if (children == null) {
                return new LspDocumentSymbolTreeElement[0];
            }
            LspDocumentSymbolTreeElement[] result = new LspDocumentSymbolTreeElement[children.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = new LspDocumentSymbolTreeElement(mySupport, children.get(i));
            }
            return result;
        }

        @Override
        public ItemPresentation getPresentation() {
            return new ColoredItemPresentation() {
                @Override
                public LocalizeValue getPresentableText() {
                    return LocalizeValue.ofNullable(mySymbol.getName());
                }

                @Override
                public @Nullable String getLocationString() {
                    return mySymbol.getDetail();
                }

                @Override
                public @Nullable Image getIcon() {
                    return mySupport.getIcon(mySymbol);
                }

                @Override
                @SuppressWarnings("deprecation")
                public @Nullable TextAttributesKey getTextAttributesKey() {
                    List<SymbolTag> tags = mySymbol.getTags();
                    boolean deprecated = tags != null
                        ? tags.contains(SymbolTag.Deprecated)
                        : mySymbol.getDeprecated() != null && mySymbol.getDeprecated();
                    return deprecated ? CodeInsightColors.DEPRECATED_ATTRIBUTES : null;
                }
            };
        }

        @Override
        public boolean canNavigate() {
            return canNavigateToSource();
        }

        @Override
        public boolean canNavigateToSource() {
            return true;
        }

        @Override
        public void navigate(boolean requestFocus) {
            mySupport.navigate(mySymbol.getSelectionRange().getStart(), requestFocus);
        }
    }
}

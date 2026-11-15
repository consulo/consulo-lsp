package com.intellij.platform.lsp.impl.features.hierarchy;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspDocumentPosition;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.language.editor.hierarchy.HierarchyNodeDescriptor;
import consulo.language.editor.hierarchy.HierarchyTreeStructure;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public abstract class LspAbstractHierarchyTreeStructure extends HierarchyTreeStructure {
    private static final LspHierarchyNodeDescriptor[] EMPTY_ARRAY = new LspHierarchyNodeDescriptor[0];

    protected LspAbstractHierarchyTreeStructure(Project project, LspHierarchyNodeDescriptor baseDescriptor) {
        super(project, baseDescriptor);
    }

    @Override
    protected LspHierarchyNodeDescriptor[] buildChildren(HierarchyNodeDescriptor descriptor) {
        if (!(descriptor instanceof LspHierarchyNodeDescriptor node)) {
            return EMPTY_ARRAY;
        }

        if (node.isBase()) {
            return buildVisibleRoot(node);
        }

        List<LspHierarchyNodeDescriptor> result = new ArrayList<>();
        for (Lsp4jHierarchyItem item : fetchItems(node)) {
            LspHierarchyNodeDescriptor child = createNodeDescriptorForItem(item, node);
            if (child != null) {
                result.add(child);
            }
        }
        return result.toArray(EMPTY_ARRAY);
    }

    protected abstract List<Lsp4jHierarchyItem> fetchItems(LspHierarchyNodeDescriptor nodeDescriptor);

    protected abstract @Nullable Lsp4jHierarchyItem fetchRootItem(LspClientImpl client, TextDocumentIdentifier textDocument, Position position);

    private @Nullable Lsp4jHierarchyItem prepareRootItem(LspHierarchyNodeDescriptor rootDescriptor) {
        PsiFile psiFile = rootDescriptor.getContainingFile();
        if (psiFile == null) {
            return null;
        }
        VirtualFile file = psiFile.getVirtualFile();
        if (file == null) {
            return null;
        }

        if (!(rootDescriptor.getPsiElement() instanceof LspFakePsiElement fakePsiElement)) {
            return null;
        }
        Position hostPosition = fakePsiElement.getLsp4jPosition();
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            return null;
        }
        Integer offset = Lsp4jUtilKt.getOffsetInDocument(document, hostPosition);
        if (offset == null) {
            return null;
        }
        LspDocumentPosition docPosition = rootDescriptor.getClient().getDocumentMapping().getDocumentPosition(file, document, offset);
        if (docPosition == null) {
            return null;
        }

        return fetchRootItem(rootDescriptor.getClient(), docPosition.getDocument().getId(), docPosition.getPosition());
    }

    private @Nullable LspHierarchyNodeDescriptor createNodeDescriptorForItem(
        Lsp4jHierarchyItem item,
        LspHierarchyNodeDescriptor parentDescriptor
    ) {
        VirtualFile file = getVirtualFileForItem(item, parentDescriptor);
        if (file == null) {
            return null;
        }
        PsiFile psiFile = PsiManager.getInstance(myProject).findFile(file);
        if (psiFile == null) {
            return null;
        }
        return createDescriptor(item, parentDescriptor, psiFile);
    }

    private LspHierarchyNodeDescriptor createDescriptor(Lsp4jHierarchyItem item, LspHierarchyNodeDescriptor parent, PsiFile psiFile) {
        return new LspHierarchyNodeDescriptor(
            parent.getClient(),
            item,
            new LspFakePsiElement(psiFile, item.getSelectionRange().getStart(), item.getName()),
            parent,
            false
        );
    }

    private LspHierarchyNodeDescriptor[] buildVisibleRoot(LspHierarchyNodeDescriptor rootDescriptor) {
        PsiFile psiFile = rootDescriptor.getContainingFile();
        if (psiFile == null) {
            return EMPTY_ARRAY;
        }
        Lsp4jHierarchyItem item = prepareRootItem(rootDescriptor);
        if (item == null) {
            return EMPTY_ARRAY;
        }
        LspHierarchyNodeDescriptor child = createDescriptor(item, rootDescriptor, psiFile);
        return new LspHierarchyNodeDescriptor[]{child};
    }

    private @Nullable VirtualFile getVirtualFileForItem(Lsp4jHierarchyItem item, LspHierarchyNodeDescriptor descriptor) {
        String uri = item.getUri();
        return descriptor.getClient().getDescriptor().findFileByUri(uri);
    }
}

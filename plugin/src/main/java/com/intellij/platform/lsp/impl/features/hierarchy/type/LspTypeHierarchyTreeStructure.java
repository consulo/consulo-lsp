package com.intellij.platform.lsp.impl.features.hierarchy.type;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.features.hierarchy.Lsp4jHierarchyItem;
import com.intellij.platform.lsp.impl.features.hierarchy.LspAbstractHierarchyTreeStructure;
import com.intellij.platform.lsp.impl.features.hierarchy.LspHierarchyNodeDescriptor;
import consulo.project.Project;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.TypeHierarchyPrepareParams;
import org.jspecify.annotations.Nullable;

import java.util.List;

public abstract class LspTypeHierarchyTreeStructure extends LspAbstractHierarchyTreeStructure {
    protected LspTypeHierarchyTreeStructure(Project project, LspHierarchyNodeDescriptor baseDescriptor) {
        super(project, baseDescriptor);
    }

    @Override
    protected @Nullable Lsp4jHierarchyItem fetchRootItem(LspClientImpl client, TextDocumentIdentifier textDocument, Position position) {
        List<TypeHierarchyItem> items = client.sendRequestSync(
            it -> it.getTextDocumentService().prepareTypeHierarchy(new TypeHierarchyPrepareParams(textDocument, position))
        );
        if (items == null || items.isEmpty()) {
            return null;
        }

        return Lsp4jHierarchyItem.from(items.get(0));
    }
}

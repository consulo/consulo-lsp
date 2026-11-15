package com.intellij.platform.lsp.impl.features.hierarchy.call;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.features.hierarchy.Lsp4jHierarchyItem;
import com.intellij.platform.lsp.impl.features.hierarchy.LspAbstractHierarchyTreeStructure;
import com.intellij.platform.lsp.impl.features.hierarchy.LspHierarchyNodeDescriptor;
import consulo.project.Project;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.jspecify.annotations.Nullable;

import java.util.List;

public abstract class LspCallHierarchyTreeStructure extends LspAbstractHierarchyTreeStructure {
    protected LspCallHierarchyTreeStructure(Project project, LspHierarchyNodeDescriptor baseDescriptor) {
        super(project, baseDescriptor);
    }

    @Override
    protected @Nullable Lsp4jHierarchyItem fetchRootItem(LspClientImpl client, TextDocumentIdentifier textDocument, Position position) {
        List<CallHierarchyItem> items = client.sendRequestSync(
            it -> it.getTextDocumentService().prepareCallHierarchy(new CallHierarchyPrepareParams(textDocument, position))
        );
        if (items == null || items.isEmpty()) {
            return null;
        }

        return Lsp4jHierarchyItem.from(items.get(0));
    }
}

package com.intellij.platform.lsp.impl.features.hierarchy.type;

import com.intellij.platform.lsp.impl.features.hierarchy.Lsp4jHierarchyItem;
import com.intellij.platform.lsp.impl.features.hierarchy.LspHierarchyNodeDescriptor;
import consulo.project.Project;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.TypeHierarchySupertypesParams;

import java.util.ArrayList;
import java.util.List;

public class LspSupertypesHierarchyTreeStructure extends LspTypeHierarchyTreeStructure {
    public LspSupertypesHierarchyTreeStructure(Project project, LspHierarchyNodeDescriptor baseDescriptor) {
        super(project, baseDescriptor);
    }

    @Override
    protected List<Lsp4jHierarchyItem> fetchItems(LspHierarchyNodeDescriptor nodeDescriptor) {
        Lsp4jHierarchyItem item = nodeDescriptor.getItem();
        TypeHierarchyItem typeHierarchyItem = item != null ? item.toTypeHierarchyItem() : null;
        if (typeHierarchyItem == null) {
            return List.of();
        }
        TypeHierarchySupertypesParams params = new TypeHierarchySupertypesParams(typeHierarchyItem);
        List<TypeHierarchyItem> items = nodeDescriptor.getClient().sendRequestSync(
            it -> it.getTextDocumentService().typeHierarchySupertypes(params)
        );
        if (items == null) {
            return List.of();
        }
        List<Lsp4jHierarchyItem> result = new ArrayList<>(items.size());
        for (TypeHierarchyItem it : items) {
            result.add(Lsp4jHierarchyItem.from(it));
        }
        return result;
    }
}

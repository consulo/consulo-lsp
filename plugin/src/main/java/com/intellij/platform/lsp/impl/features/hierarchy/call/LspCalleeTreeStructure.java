package com.intellij.platform.lsp.impl.features.hierarchy.call;

import com.intellij.platform.lsp.impl.features.hierarchy.Lsp4jHierarchyItem;
import com.intellij.platform.lsp.impl.features.hierarchy.LspHierarchyNodeDescriptor;
import consulo.project.Project;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;

import java.util.ArrayList;
import java.util.List;

public class LspCalleeTreeStructure extends LspCallHierarchyTreeStructure {
    public LspCalleeTreeStructure(Project project, LspHierarchyNodeDescriptor baseDescriptor) {
        super(project, baseDescriptor);
    }

    @Override
    protected List<Lsp4jHierarchyItem> fetchItems(LspHierarchyNodeDescriptor nodeDescriptor) {
        Lsp4jHierarchyItem item = nodeDescriptor.getItem();
        CallHierarchyItem callHierarchyItem = item != null ? item.toCallHierarchyItem() : null;
        if (callHierarchyItem == null) {
            return List.of();
        }
        CallHierarchyOutgoingCallsParams params = new CallHierarchyOutgoingCallsParams(callHierarchyItem);
        List<CallHierarchyOutgoingCall> calls = nodeDescriptor.getClient().sendRequestSync(
            it -> it.getTextDocumentService().callHierarchyOutgoingCalls(params)
        );
        if (calls == null) {
            return List.of();
        }
        List<Lsp4jHierarchyItem> result = new ArrayList<>(calls.size());
        for (CallHierarchyOutgoingCall call : calls) {
            result.add(Lsp4jHierarchyItem.from(call.getTo()));
        }
        return result;
    }
}

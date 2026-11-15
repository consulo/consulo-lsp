package com.intellij.platform.lsp.impl.features.hierarchy.call;

import com.intellij.platform.lsp.impl.features.hierarchy.Lsp4jHierarchyItem;
import com.intellij.platform.lsp.impl.features.hierarchy.LspHierarchyNodeDescriptor;
import consulo.project.Project;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;

import java.util.ArrayList;
import java.util.List;

public class LspCallerTreeStructure extends LspCallHierarchyTreeStructure {
    public LspCallerTreeStructure(Project project, LspHierarchyNodeDescriptor baseDescriptor) {
        super(project, baseDescriptor);
    }

    @Override
    protected List<Lsp4jHierarchyItem> fetchItems(LspHierarchyNodeDescriptor nodeDescriptor) {
        Lsp4jHierarchyItem item = nodeDescriptor.getItem();
        CallHierarchyItem callHierarchyItem = item != null ? item.toCallHierarchyItem() : null;
        if (callHierarchyItem == null) {
            return List.of();
        }
        CallHierarchyIncomingCallsParams params = new CallHierarchyIncomingCallsParams(callHierarchyItem);
        List<CallHierarchyIncomingCall> calls = nodeDescriptor.getClient().sendRequestSync(
            it -> it.getTextDocumentService().callHierarchyIncomingCalls(params)
        );
        if (calls == null) {
            return List.of();
        }
        List<Lsp4jHierarchyItem> result = new ArrayList<>(calls.size());
        for (CallHierarchyIncomingCall call : calls) {
            result.add(Lsp4jHierarchyItem.from(call.getFrom()));
        }
        return result;
    }
}

package com.intellij.platform.lsp.impl.util;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.customization.LspIntentionAction;
import consulo.annotation.access.RequiredReadAction;
import consulo.ui.annotation.RequiredUIAccess;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.jspecify.annotations.Nullable;

public class LspWorkspaceEditApplier {
    private final LspIntentionAction myIntentionAction;

    private LspWorkspaceEditApplier(LspIntentionAction intentionAction) {
        myIntentionAction = intentionAction;
    }

    @RequiredUIAccess
    public void applyWorkspaceEdit() {
        myIntentionAction.invoke(null);
    }

    @RequiredReadAction
    public static @Nullable LspWorkspaceEditApplier create(LspClient lspClient, WorkspaceEdit workspaceEdit) {
        CodeAction codeAction = new CodeAction();
        codeAction.setEdit(workspaceEdit);
        LspIntentionAction intentionAction = new LspIntentionAction(lspClient, codeAction);
        return intentionAction.isAvailable() ? new LspWorkspaceEditApplier(intentionAction) : null;
    }
}

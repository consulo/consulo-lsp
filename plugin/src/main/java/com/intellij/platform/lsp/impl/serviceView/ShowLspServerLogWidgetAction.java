package com.intellij.platform.lsp.impl.serviceView;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.localize.LocalizeValue;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.DumbAwareAction;

/**
 * Shows the {@link LspServerLog} from the item of the server in the Language Services widget.
 * <p>
 * The widget creates its items again each time it opens, so the action looks for the log only when it runs.
 */
public class ShowLspServerLogWidgetAction extends DumbAwareAction {
    private final LspClient myLspClient;

    public ShowLspServerLogWidgetAction(LspClient lspClient) {
        super(
            LspLocalize.actionShowlspserverlogwidgetactionText(),
            LocalizeValue.empty(),
            PlatformIconGroup.toolwindowsToolwindowmessages()
        );
        myLspClient = lspClient;
    }

    public LspServerLog serverLog() {
        return new LspServerLog((LspClientImpl) myLspClient);
    }

    @Override
    @RequiredUIAccess
    public void actionPerformed(AnActionEvent e) {
        serverLog().show();
    }
}

package com.intellij.platform.lsp.impl.serviceView;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.project.ui.notification.Notification;
import consulo.project.ui.notification.NotificationAction;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;

/**
 * Shows the {@link LspServerLog} from an error or warning notification of the server.
 */
public class ShowLspServerLogAction extends NotificationAction {
    private final LspServerLog myServerLog;

    public ShowLspServerLogAction(LspClientImpl lspClient) {
        super(LspLocalize.actionShowlspserverlogactionText());
        myServerLog = new LspServerLog(lspClient);
    }

    public LspServerLog getServerLog() {
        return myServerLog;
    }

    @Override
    @RequiredUIAccess
    public void actionPerformed(AnActionEvent e, Notification notification) {
        myServerLog.show();
    }
}

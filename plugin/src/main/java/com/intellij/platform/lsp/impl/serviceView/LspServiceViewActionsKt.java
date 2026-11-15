package com.intellij.platform.lsp.impl.serviceView;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.localize.LocalizeValue;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.AnActionWithSyncUpdate;
import consulo.ui.ex.action.DefaultActionGroup;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.util.dataholder.Key;
import consulo.util.lang.lazy.LazyValue;

import java.util.function.Supplier;

/**
 * Shared members of the Services view actions, see also {@link LspServerLog}, {@link ShowLspServerLogAction} and
 * {@link ShowLspServerLogWidgetAction}.
 */
public final class LspServiceViewActionsKt {
    public static final Key<LspClient> LSP_CLIENT_DATA_KEY = Key.create("lsp.serviceView.client");

    // Toolbar action instances must not change on every toolbar update, so a single shared group is used by all descriptors
    private static final Supplier<DefaultActionGroup> LSP_CLIENT_SERVICE_VIEW_ACTION_GROUP =
        LazyValue.notNull(() -> new DefaultActionGroup(new RestartLspClientServiceAction(), new StopLspClientServiceAction()));

    private LspServiceViewActionsKt() {
    }

    public static DefaultActionGroup getLspClientServiceViewActionGroup() {
        return LSP_CLIENT_SERVICE_VIEW_ACTION_GROUP.get();
    }

    private static class RestartLspClientServiceAction extends DumbAwareAction implements AnActionWithSyncUpdate {
        RestartLspClientServiceAction() {
            super(LspLocalize.actionRestartlspserveractionText(), LocalizeValue.empty(), PlatformIconGroup.actionsRestart());
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(e.getData(LSP_CLIENT_DATA_KEY) != null);
        }

        @Override
        @RequiredUIAccess
        public void actionPerformed(AnActionEvent e) {
            if (!(e.getData(LSP_CLIENT_DATA_KEY) instanceof LspClientImpl lspClient)) {
                return;
            }
            LspClientManagerImpl.getInstanceImpl(lspClient.getProject()).restartClient(lspClient);
        }
    }

    private static class StopLspClientServiceAction extends DumbAwareAction implements AnActionWithSyncUpdate {
        StopLspClientServiceAction() {
            super(LspLocalize.actionServicesLspStopText(), LocalizeValue.empty(), PlatformIconGroup.actionsSuspend());
        }

        @Override
        public void update(AnActionEvent e) {
            LspClient lspClient = e.getData(LSP_CLIENT_DATA_KEY);
            LspServerState state = lspClient != null ? lspClient.getState() : null;
            e.getPresentation().setEnabledAndVisible(state == LspServerState.Initializing || state == LspServerState.Running);
        }

        @Override
        @RequiredUIAccess
        public void actionPerformed(AnActionEvent e) {
            if (!(e.getData(LSP_CLIENT_DATA_KEY) instanceof LspClientImpl lspClient)) {
                return;
            }
            LspClientManagerImpl.getInstanceImpl(lspClient.getProject()).stopRunningServer(lspClient);
        }
    }
}

package com.intellij.platform.lsp.impl.lsWidget;

import com.intellij.platform.lang.localize.LangLocalize;
import com.intellij.platform.lang.lsWidget.impl.LanguageServiceNotificationGroupContributor;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.lsWidget.LspWidgetInternalService;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.serviceView.ShowLspServerLogWidgetAction;
import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.annotation.component.ServiceImpl;
import consulo.fileEditor.FileEditorManager;
import consulo.language.file.light.LightVirtualFile;
import consulo.language.plain.PlainTextFileType;
import consulo.localize.LocalizeValue;
import consulo.navigation.OpenFileDescriptor;
import consulo.navigation.OpenFileDescriptorFactory;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.ui.notification.NotificationService;
import consulo.project.ui.notification.NotificationType;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnAction;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.DumbAwareAction;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

/**
 * Unlike {@link LspWidgetInternalService}, this class is located in the implementation module,
 * so it has access to {@link LspClientImpl} and other classes that are not available in the public LSP API.
 */
@ServiceImpl
@Singleton
public class LspWidgetInternalServiceImpl extends LspWidgetInternalService {
    private final NotificationService myNotificationService;

    @Inject
    public LspWidgetInternalServiceImpl(NotificationService notificationService) {
        myNotificationService = notificationService;
    }

    @Override
    public @Nullable AnAction createShowErrorOutputAction(LspClient lspClient) {
        LspClientImpl lspClientImpl = (LspClientImpl) lspClient;
        if (lspClientImpl.getErrorOutput() == null) {
            return null;
        }
        return new OpenLspErrorOutputAction(lspClientImpl);
    }

    @Override
    public AnAction createShowServerLogAction(LspClient lspClient) {
        return new ShowLspServerLogWidgetAction(lspClient);
    }

    @Override
    public void restartLspClient(LspClient lspClient) {
        LspClientManagerImpl.getInstanceImpl(lspClient.getProject()).restartClient((LspClientImpl) lspClient);

        LocalizeValue title = LangLocalize.languageServices0ServerRestartedNotificationTitle(lspClient.getDescriptor().getPresentableName());
        myNotificationService.newOfType(LanguageServiceNotificationGroupContributor.SERVICE_STOPPED_OR_RESTARTED_GROUP, NotificationType.INFORMATION)
            .title(title)
            .create()
            .notify(lspClient.getProject());
    }

    @Override
    public void stopLspClient(LspClient lspClient) {
        LspClientManagerImpl.getInstanceImpl(lspClient.getProject()).stopRunningServer((LspClientImpl) lspClient);

        LocalizeValue title = LangLocalize.languageServices0ServerStoppedNotificationTitle(lspClient.getDescriptor().getPresentableName());
        LocalizeValue content = LangLocalize.languageServicesServerStoppedNotificationContent();
        myNotificationService.newOfType(LanguageServiceNotificationGroupContributor.SERVICE_STOPPED_OR_RESTARTED_GROUP, NotificationType.INFORMATION)
            .title(title)
            .content(content)
            .create()
            .notify(lspClient.getProject());
    }

    private static class OpenLspErrorOutputAction extends DumbAwareAction {
        private final LspClientImpl myLspClient;

        OpenLspErrorOutputAction(LspClientImpl lspClient) {
            super(LspLocalize.actionOpenlsperroroutputactionText(), LocalizeValue.empty(), PlatformIconGroup.generalError());
            myLspClient = lspClient;
        }

        @Override
        @RequiredUIAccess
        public void actionPerformed(AnActionEvent e) {
            openLspServerErrorOutput(myLspClient);
        }
    }

    /**
     * Opens the last part of the server error output ({@code stderr}) of {@code lspClient} in an editor tab.
     */
    @RequiredUIAccess
    public static void openLspServerErrorOutput(LspClientImpl lspClient) {
        String fileName = LspLocalize.serverErrorOutputEditorTabName(lspClient.getDescriptor().getPresentableName()).get();
        String errorOutput = lspClient.getErrorOutput();
        LightVirtualFile file = new LightVirtualFile(fileName, PlainTextFileType.INSTANCE, errorOutput != null ? errorOutput : "");
        FileEditorManager fileEditorManager = FileEditorManager.getInstance(lspClient.getProject());
        OpenFileDescriptor openDescriptor = OpenFileDescriptorFactory.getInstance(lspClient.getProject()).newBuilder(file).build();
        fileEditorManager.openTextEditor(openDescriptor, true);
    }
}

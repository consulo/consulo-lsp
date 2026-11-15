package com.intellij.platform.lsp.impl.serviceView;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspClientManager;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.dataContext.DataSink;
import consulo.dataContext.UiDataProvider;
import consulo.execution.ExecutionUtil;
import consulo.execution.service.ServiceViewDescriptor;
import consulo.navigation.ItemPresentation;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.SimpleTextAttributes;
import consulo.ui.ex.action.ActionGroup;
import consulo.ui.ex.tree.PresentationData;
import consulo.ui.image.Image;
import consulo.ui.image.ImageEffects;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerInfo;
import org.jspecify.annotations.Nullable;

import javax.swing.JComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LspClientServiceViewDescriptor implements ServiceViewDescriptor, UiDataProvider {
    private final Project myProject;
    private final LspClient myLspClient;

    // ServiceView recreates this descriptor on each SERVICE_CHANGED event, so the presentation reflects the current client state
    private final ItemPresentation myPresentation;

    public LspClientServiceViewDescriptor(Project project, LspClient lspClient) {
        myProject = project;
        myLspClient = lspClient;
        myPresentation = createPresentation();
    }

    @Override
    public ItemPresentation getPresentation() {
        return myPresentation;
    }

    @Override
    public String getId() {
        return myLspClient.getProviderClass().getName() +
            "/" + myLspClient.getDescriptor().getPresentableName() +
            "/" + Stream.of(myLspClient.getDescriptor().getRoots()).map(VirtualFile::getPath).collect(Collectors.joining(","));
    }

    @Override
    @RequiredUIAccess
    public @Nullable JComponent getContentComponent() {
        LspClientConsole console = LspServiceViewSupport.getInstance(myProject).getOrCreateConsole(myLspClient);
        return console != null ? console.getComponent() : null;
    }

    @Override
    public ActionGroup getToolbarActions() {
        return LspServiceViewActionsKt.getLspClientServiceViewActionGroup();
    }

    @Override
    public void uiDataSnapshot(DataSink sink) {
        sink.set(LspServiceViewActionsKt.LSP_CLIENT_DATA_KEY, myLspClient);
    }

    private ItemPresentation createPresentation() {
        PresentationData presentationData = new PresentationData();
        LspServerState state = myLspClient.getState();

        Image baseIcon = PlatformIconGroup.webreferencesServer();
        presentationData.setIcon(switch (state) {
            case Initializing -> baseIcon;
            case Running -> ExecutionUtil.getIconWithLiveIndicator(baseIcon);
            case ShutdownNormally -> ImageEffects.grayed(baseIcon);
            case ShutdownUnexpectedly -> ImageEffects.layered(baseIcon, PlatformIconGroup.nodesErrormark());
        });

        presentationData.addText(myLspClient.getDescriptor().getPresentableName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);

        List<String> details = new ArrayList<>();
        InitializeResult initializeResult = myLspClient.getInitializeResult();
        ServerInfo serverInfo = initializeResult != null ? initializeResult.getServerInfo() : null;
        if (serverInfo != null && serverInfo.getVersion() != null) {
            details.add(serverInfo.getVersion());
        }
        String rootPostfix = rootPostfix();
        if (rootPostfix != null) {
            details.add(rootPostfix);
        }
        switch (state) {
            case Initializing -> details.add(LspLocalize.servicesLspStateInitializing().get());
            case Running -> {
            }
            case ShutdownNormally -> details.add(LspLocalize.servicesLspStateStopped().get());
            case ShutdownUnexpectedly -> details.add(LspLocalize.servicesLspStateTerminated().get());
        }
        if (!details.isEmpty()) {
            String detailsText = " " + String.join(" ", details);
            presentationData.addText(detailsText, SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }

        return presentationData;
    }

    private @Nullable String rootPostfix() {
        VirtualFile[] roots = myLspClient.getDescriptor().getRoots();
        Collection<LspClient> lspClients = LspClientManager.getInstance(myProject).getClients(myLspClient.getProviderClass());
        return lspClients.size() >= 2 && roots.length == 1 ? "…/" + roots[0].getName() : null;
    }
}

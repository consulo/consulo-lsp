package com.intellij.platform.lsp.impl.serviceView;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.annotation.component.ExtensionImpl;
import consulo.execution.service.ServiceViewContributor;
import consulo.execution.service.ServiceViewDescriptor;
import consulo.execution.service.ServiceViewNonActivatingDescriptor;
import consulo.execution.service.SimpleServiceViewDescriptor;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.Project;

import java.util.ArrayList;
import java.util.List;

@ExtensionImpl
public class LspServiceViewContributor implements ServiceViewContributor<LspClient> {
    @Override
    public ServiceViewDescriptor getViewDescriptor(Project project) {
        return new LspRootDescriptor();
    }

    @Override
    public List<LspClient> getServices(Project project) {
        return new ArrayList<>(LspClientManagerImpl.getInstanceImpl(project).getAllClients());
    }

    @Override
    public ServiceViewDescriptor getServiceDescriptor(Project project, LspClient service) {
        return new LspClientServiceViewDescriptor(project, service);
    }

    private static class LspRootDescriptor extends SimpleServiceViewDescriptor implements ServiceViewNonActivatingDescriptor {
        LspRootDescriptor() {
            super(LspLocalize.servicesLspRootNode().get(), PlatformIconGroup.webreferencesServer(), "LSP");
        }
    }
}

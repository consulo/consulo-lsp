// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.lsp.ui.frontend;

import com.intellij.lsp.ui.frontend.settings.LspIntegrationSettingsImpl;
import com.intellij.lsp.ui.frontend.settings.LspServerConfiguration;
import com.intellij.platform.lsp.api.LspBulkIntegrationProvider;
import com.intellij.platform.lsp.api.LspIntegrationProvider;
import consulo.annotation.component.ExtensionImpl;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;

@ExtensionImpl
public class ConfigurableLspIntegrationProvider implements LspBulkIntegrationProvider {
    @Override
    public void fileOpened(Project project, VirtualFile file, LspIntegrationProvider.LspClientStarter clientStarter) {
        LspIntegrationSettingsImpl settings = LspIntegrationSettingsImpl.getInstance(project);
        for (LspServerConfiguration configuration : settings.getServers()) {
            if (!configuration.getEnabled()) {
                continue;
            }
            ConfigurableLspClientDescriptor descriptor = new ConfigurableLspClientDescriptor(project, configuration);
            if (descriptor.isSupportedFile(file)) {
                clientStarter.ensureClientStarted(descriptor);
            }
        }
    }

    // TODO lsWidget stage: upstream `createWidgetItem(lspClient, currentFile) =
    //  LspClientWidgetItem(lspClient, currentFile, settingsPageClass = LspServersConfigurable::class.java)`.
    //  LspIntegrationProvider has no widget item methods yet (see LspIntegrationProvider).
}

// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.lsp.ui.frontend;

import com.intellij.lsp.ui.frontend.settings.LspIntegrationSettingsImpl;
import com.intellij.lsp.ui.frontend.settings.LspServerConfiguration;
import com.intellij.lsp.ui.frontend.settings.LspServersConfigurable;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspBulkIntegrationProvider;
import com.intellij.platform.lsp.api.LspIntegrationProvider;
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem;
import consulo.annotation.component.ExtensionImpl;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

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

    @Override
    public LspClientWidgetItem createWidgetItem(LspClient lspClient, @Nullable VirtualFile currentFile) {
        return new LspClientWidgetItem(lspClient, currentFile, PlatformIconGroup.webreferencesServer(), LspServersConfigurable.class);
    }
}

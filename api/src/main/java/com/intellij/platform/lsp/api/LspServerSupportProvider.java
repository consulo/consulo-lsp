// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ExtensionAPI;
import consulo.component.extension.ExtensionPointName;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;

/**
 * @deprecated Renamed to {@link LspIntegrationProvider}
 */
@Deprecated
@SuppressWarnings("deprecation")
@ExtensionAPI(ComponentScope.APPLICATION)
public interface LspServerSupportProvider extends LspIntegrationProvider {
    /**
     * @deprecated Use {@link LspIntegrationProvider#EP_NAME}
     */
    @Deprecated
    ExtensionPointName<LspServerSupportProvider> EP_NAME = ExtensionPointName.create(LspServerSupportProvider.class);

    /**
     * @deprecated Renamed to {@link LspIntegrationProvider.LspClientStarter}
     */
    @Deprecated
    interface LspServerStarter {
        void ensureServerStarted(LspServerDescriptor descriptor);
    }

    /**
     * Called in a background thread.
     */
    @RequiredReadAction
    void fileOpened(Project project, VirtualFile file, LspServerStarter serverStarter);

    // TODO lsWidget stage: upstream `createLspWidgetItems(project, currentFile)` and `createLspServerWidgetItem(lspServer, currentFile)`,
    //  and the overrides of `createWidgetItems`/`createWidgetItem` (see LspIntegrationProvider).

    @Override
    @RequiredReadAction
    default void fileOpened(Project project, VirtualFile file, LspIntegrationProvider.LspClientStarter clientStarter) {
        fileOpened(project, file, (LspServerStarter) clientStarter);
    }
}

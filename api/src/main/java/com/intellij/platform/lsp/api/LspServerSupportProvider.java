// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import com.intellij.platform.lang.lsWidget.LanguageServiceWidgetItem;
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem;
import com.intellij.platform.lsp.api.lsWidget.LspServerWidgetItem;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ExtensionAPI;
import consulo.component.extension.ExtensionPointName;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

import java.util.List;

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

    /**
     * Defers to {@link LspIntegrationProvider}'s enumeration via {@code super} (so the {@link #createWidgetItems} override below isn't re-entered).
     * That enumeration maps each client through {@link #createWidgetItem}, so overriding the new {@link #createWidgetItem} *or* the
     * deprecated {@link #createLspServerWidgetItem} both take effect.
     */
    default List<LanguageServiceWidgetItem> createLspWidgetItems(Project project, @Nullable VirtualFile currentFile) {
        return LspIntegrationProvider.super.createWidgetItems(project, currentFile);
    }

    default @Nullable LspServerWidgetItem createLspServerWidgetItem(LspServer lspServer, @Nullable VirtualFile currentFile) {
        return new LspServerWidgetItem(lspServer, currentFile);
    }

    @Override
    @RequiredReadAction
    default void fileOpened(Project project, VirtualFile file, LspIntegrationProvider.LspClientStarter clientStarter) {
        fileOpened(project, file, (LspServerStarter) clientStarter);
    }

    @Override
    default List<LanguageServiceWidgetItem> createWidgetItems(Project project, @Nullable VirtualFile currentFile) {
        return createLspWidgetItems(project, currentFile);
    }

    @Override
    default @Nullable LspClientWidgetItem createWidgetItem(LspClient lspClient, @Nullable VirtualFile currentFile) {
        return createLspServerWidgetItem((LspServer) lspClient, currentFile);
    }
}

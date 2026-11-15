// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.structureView;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspClientManager;
import com.intellij.platform.lsp.api.LspClientManagerListener;
import com.intellij.platform.lsp.api.LspServerState;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.Application;
import consulo.application.dumb.DumbAware;
import consulo.fileEditor.FileEditor;
import consulo.fileEditor.FileEditorManager;
import consulo.fileEditor.structureView.StructureViewWrapper;
import consulo.language.editor.structureView.StructureViewFactoryEx;
import consulo.project.Project;
import consulo.project.startup.PostStartupActivity;
import consulo.ui.UIAccess;
import consulo.virtualFileSystem.VirtualFile;

/**
 * Consulo: upstream {@code ProjectActivity} became a {@link PostStartupActivity}.
 */
@ExtensionImpl
public class LspStructureViewProjectActivity implements PostStartupActivity, DumbAware {
    @Override
    public void runActivity(Project project, UIAccess uiAccess) {
        LspClientManager.getInstance(project).addListener(
            new LspClientManagerListener() {
                @Override
                public void serverStateChanged(LspClient lspClient) {
                    if (lspClient.getState() == LspServerState.Running ||
                        lspClient.getState() == LspServerState.ShutdownNormally ||
                        lspClient.getState() == LspServerState.ShutdownUnexpectedly) {
                        refreshStructureView(project);
                    }
                }

                @Override
                public void fileOpened(LspClient lspClient, VirtualFile file) {
                    refreshStructureView(project);
                }
            },
            project,
            true
        );
    }

    /**
     * Consulo: there is no {@code StructureViewWrapperImpl.STRUCTURE_CHANGED} topic. Selecting the current element
     * of the selected editor makes the structure view wrapper rebuild when it showed no structure for that editor before
     * (the LSP structure view builder was not available yet).
     */
    private void refreshStructureView(Project project) {
        Application.get().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            StructureViewWrapper wrapper = StructureViewFactoryEx.getInstanceEx(project).getStructureViewWrapper();
            if (wrapper == null) {
                return;
            }
            FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);
            VirtualFile[] selectedFiles = fileEditorManager.getSelectedFiles();
            if (selectedFiles.length == 0) {
                return;
            }
            FileEditor fileEditor = fileEditorManager.getSelectedEditor(selectedFiles[0]);
            if (fileEditor != null) {
                wrapper.selectCurrentElement(fileEditor, selectedFiles[0], false);
            }
        });
    }
}

package com.intellij.platform.lsp.impl.platformListeners;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.documentSync.LspOpenedFilesService;
import com.intellij.platform.lsp.impl.features.inlayCommon.LspInlayApplier;
import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.TopicImpl;
import consulo.application.WriteAction;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.fileEditor.FileEditorManager;
import consulo.fileEditor.event.FileEditorManagerEvent;
import consulo.fileEditor.event.FileEditorManagerListener;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;

import java.util.Collection;
import java.util.List;

/**
 * Starts the LSP servers when supported files are opened in the editor.
 */
@TopicImpl(ComponentScope.PROJECT)
public class LspFileEditorManagerListener implements FileEditorManagerListener {
    @Override
    public void fileOpened(FileEditorManager fileEditorManager, VirtualFile file) {
        Project project = fileEditorManager.getProject();
        if (project.isDefault()) {
            return;
        }
        LspOpenedFilesService.getInstance(project).processOpenedFiles(List.of(file));
    }

    @Override
    public void selectionChanged(FileEditorManagerEvent event) {
        Project project = event.getManager().getProject();
        if (project.isDefault()) {
            return;
        }
        VirtualFile file = event.getNewFile();
        if (file == null) {
            return;
        }
        LspOpenedFilesService.getInstance(project).processOpenedFiles(List.of(file));
    }

    @Override
    public void fileClosed(FileEditorManager fileEditorManager, VirtualFile file) {
        Project project = fileEditorManager.getProject();
        if (project.isDefault()) {
            return;
        }
        if (fileEditorManager.isFileOpen(file)) {
            return; // the file might be still open in some other editor
        }
        LspInlayApplier.getInstance(project).onFileClosed(file);
        Document document = FileDocumentManager.getInstance().getCachedDocument(file);
        if (document == null) {
            return;
        }
        if (FileDocumentManager.getInstance().isDocumentUnsaved(document)) {
            return;
        }
        Collection<LspClientImpl> clientsToSendDidClose = LspClientManagerImpl.getInstanceImpl(project).getClientsWithThisFileOpen(file);
        if (!clientsToSendDidClose.isEmpty()) {
            WriteAction.run(() -> clientsToSendDidClose.forEach(it -> it.getDocumentSyncManager().close(file)));
        }
    }
}

package com.intellij.platform.lsp.impl.platformListeners;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.documentSync.LspOpenedFilesService;
import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.TopicImpl;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.document.event.FileDocumentManagerListener;
import consulo.fileEditor.FileEditorManager;
import consulo.project.Project;
import consulo.project.ProjectManager;
import consulo.virtualFileSystem.VirtualFile;

/**
 * Upstream: {@code <applicationListeners><listener topic="com.intellij.openapi.fileEditor.FileDocumentManagerListener"/>}.
 */
@TopicImpl(ComponentScope.APPLICATION)
public class LspFileDocumentManagerListener implements FileDocumentManagerListener {
    /**
     * Upstream: {@code unsavedDocumentDropped(document) = beforeDocumentSaving(document)}.
     * Consulo reports only {@link #unsavedDocumentsDropped()} without the documents, so the files that are not of interest
     * any more are closed for every project.
     */
    @Override
    public void unsavedDocumentsDropped() {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed() || project.isDefault()) {
                continue;
            }
            LspOpenedFilesService.getInstance(project).scheduleClosingFilesThatAreNotOfInterest();
        }
    }

    @Override
    public void beforeDocumentSaving(Document document) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null) {
            return;
        }

        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed() || project.isDefault()) {
                continue;
            }
            for (LspClientImpl lspClient : LspClientManagerImpl.getInstanceImpl(project).getClientsWithThisFileOpen(file)) {
                lspClient.getDocumentSyncManager().scheduleSave(file, document);
            }

            if (!FileEditorManager.getInstance(project).isFileOpen(file)) {
                LspOpenedFilesService.getInstance(project).scheduleClosingFilesThatAreNotOfInterest();
            }
        }
    }
}

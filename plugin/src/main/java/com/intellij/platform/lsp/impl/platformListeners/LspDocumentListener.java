package com.intellij.platform.lsp.impl.platformListeners;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.documentSync.LspDidChangeUtil;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.ReadAction;
import consulo.application.WriteAction;
import consulo.application.progress.ProgressManager;
import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.codeEditor.event.EditorDocumentListener;
import consulo.document.FileDocumentManager;
import consulo.document.event.DocumentEvent;
import consulo.ui.ModalityState;
import consulo.virtualFileSystem.VirtualFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Upstream: {@code <editorFactoryDocumentListener id="LspDocumentListener"/>}.
 */
@ExtensionImpl(id = "LspDocumentListener")
public class LspDocumentListener implements EditorDocumentListener {
    private record ClientAndFile(LspClientImpl client, VirtualFile file) {
    }

    private static final class ChangedFilesData {
        // upstream: VirtualFileSetFactory.getInstance().createCompactVirtualFileSet()
        final Set<VirtualFile> handledFiles = new HashSet<>();
        final List<ClientAndFile> clientsToSendDidOpen = new ArrayList<>();
    }

    private final Set<VirtualFile> myFilesToHandle = Collections.synchronizedSet(new HashSet<>());

    @Override
    public void beforeDocumentChange(DocumentEvent event) {
        VirtualFile file = LspDidChangeUtil.getFileToHandle(event);
        if (file == null) {
            return;
        }
        LspClientManagerImpl.forEachRunningClientInEachProject(file, client -> client.getDocumentSyncManager().onBeforeDocumentChange(event, file));
    }

    @Override
    public void documentChanged(DocumentEvent event) {
        VirtualFile file = LspDidChangeUtil.getFileToHandle(event);
        if (file == null) {
            return;
        }

        LspClientManagerImpl.forEachRunningClientInEachProject(file, client -> client.getDocumentSyncManager().onDocumentChanged(event, file));

        VirtualFile changedFile = FileDocumentManager.getInstance().getFile(event.getDocument());
        if (changedFile != null) {
            myFilesToHandle.add(changedFile);
        }
        scheduleEventsProcessing();
    }

    private void scheduleEventsProcessing() {
        ReadAction
            .nonBlocking(() -> {
                ChangedFilesData data = new ChangedFilesData();

                synchronized (myFilesToHandle) {
                    data.handledFiles.addAll(myFilesToHandle);
                }

                FileDocumentManager fileDocumentManager = FileDocumentManager.getInstance();
                LspClientManagerImpl.forEachRunningClientInEachProject(client -> {
                    for (VirtualFile file : data.handledFiles) {
                        ProgressManager.checkCanceled();
                        if (!fileDocumentManager.isFileModified(file)) {
                            continue;  // didOpen not needed
                        }

                        if (!client.isFileOpened(file) && client.isSupportedFile(file)) {
                            data.clientsToSendDidOpen.add(new ClientAndFile(client, file));
                        }
                    }
                });

                return data;
            })
            .coalesceBy(this)
            .finishOnUiThread(application -> ModalityState.nonModal(), (ChangedFilesData data) -> {
                myFilesToHandle.removeAll(data.handledFiles);
                if (data.clientsToSendDidOpen.isEmpty()) {
                    return;
                }

                WriteAction.run(() -> {
                    for (ClientAndFile clientAndFile : data.clientsToSendDidOpen) {
                        clientAndFile.client().getDocumentSyncManager().open(clientAndFile.file());
                    }
                });
            })
            .submit(AppExecutorUtil.getAppExecutorService());
    }
}

package com.intellij.platform.lsp.impl.platformListeners;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspDynamicCapabilities;
import com.intellij.platform.lsp.impl.fileEvents.FileChangeInfo;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.Application;
import consulo.util.collection.MultiMap;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.event.AsyncFileListener;
import consulo.virtualFileSystem.event.VFileContentChangeEvent;
import consulo.virtualFileSystem.event.VFileCopyEvent;
import consulo.virtualFileSystem.event.VFileCreateEvent;
import consulo.virtualFileSystem.event.VFileDeleteEvent;
import consulo.virtualFileSystem.event.VFileEvent;
import consulo.virtualFileSystem.event.VFileMoveEvent;
import consulo.virtualFileSystem.event.VFilePropertyChangeEvent;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import org.eclipse.lsp4j.FileChangeType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code LspFileListener} solves two problems: sends {@code didClose}/{@code didOpen} notifications about opened files,
 * and {@code didChangeWatchedFiles} notifications about all file system events for all interested servers.
 */
@ExtensionImpl(id = "LspFileListener")
public class LspFileListener implements AsyncFileListener {
    @Override
    public @Nullable ChangeApplier prepareChange(List<? extends VFileEvent> events) {
        if (!LspClientManagerImpl.isAnyServerRunning()) {
            return null;
        }

        Set<VirtualFile> renamedFilesAndDirs = new LinkedHashSet<>();
        List<VFileEvent> deleteCreateCopyChangeEvents = new ArrayList<>();

        for (VFileEvent event : events) {
            if (event instanceof VFileMoveEvent moveEvent) {
                renamedFilesAndDirs.add(moveEvent.getFile());
            }
            else if (event instanceof VFilePropertyChangeEvent propertyChangeEvent && propertyChangeEvent.isRename()) {
                renamedFilesAndDirs.add(propertyChangeEvent.getFile());
            }
            else if (event instanceof VFileDeleteEvent ||
                event instanceof VFileCreateEvent ||
                event instanceof VFileCopyEvent ||
                event instanceof VFileContentChangeEvent) {
                deleteCreateCopyChangeEvents.add(event);
            }
        }

        if (!renamedFilesAndDirs.isEmpty() || !deleteCreateCopyChangeEvents.isEmpty()) {
            return new FileChangeApplier(renamedFilesAndDirs, deleteCreateCopyChangeEvents);
        }

        return null;
    }

    private static final class FileChangeApplier implements ChangeApplier {
        private final Set<VirtualFile> myRenamedFilesAndDirs;
        private final List<VFileEvent> myDeleteCreateCopyChangeEvents;
        private final Set<LspClientImpl> myClientsToUpdateOpenedFiles = new HashSet<>();
        private final MultiMap<LspClientImpl, FileChangeInfo> myClientToFileChangeInfos = MultiMap.create();

        FileChangeApplier(Set<VirtualFile> renamedFilesAndDirs, List<VFileEvent> deleteCreateCopyChangeEvents) {
            myRenamedFilesAndDirs = renamedFilesAndDirs;
            myDeleteCreateCopyChangeEvents = deleteCreateCopyChangeEvents;
        }

        private void addChange(LspClientImpl client, VirtualFile file, FileChangeType changeType) {
            String uri = client.getDescriptor().getFileUri(file);
            myClientToFileChangeInfos.putValue(client, new FileChangeInfo(client.getDescriptor().getFilePath(file), uri, file.isDirectory(), changeType));
        }

        @Override
        public void beforeVfsChange() {
            LspClientManagerImpl.forEachRunningClientInEachProject(client -> {
                boolean handleFileEvents = client.getDynamicCapabilities().hasCapability(LspDynamicCapabilities.getDidChangeWatchedFiles());

                if (handleFileEvents) {
                    for (VFileEvent event : myDeleteCreateCopyChangeEvents) {
                        if (event instanceof VFileDeleteEvent deleteEvent) {
                            addChange(client, deleteEvent.getFile(), FileChangeType.Deleted);
                        }
                    }
                }

                for (VirtualFile fileOrDir : myRenamedFilesAndDirs) {
                    if (handleFileEvents) {
                        addChange(client, fileOrDir, FileChangeType.Deleted);
                    }

                    List<VirtualFile> filesToClose = new ArrayList<>();
                    client.forEachOpenedFile(openedFile -> {
                        if (VirtualFileUtil.isAncestor(fileOrDir, openedFile, false)) {
                            myClientsToUpdateOpenedFiles.add(client);
                            filesToClose.add(openedFile);
                        }
                    });
                    filesToClose.forEach(it -> client.getDocumentSyncManager().close(it));
                }
            });
        }

        @Override
        public void afterVfsChange() {
            LspClientManagerImpl.forEachRunningClientInEachProject(client -> {
                if (!client.getDynamicCapabilities().hasCapability(LspDynamicCapabilities.getDidChangeWatchedFiles())) {
                    return;
                }

                for (VirtualFile fileOrDir : myRenamedFilesAndDirs) {
                    addChange(client, fileOrDir, FileChangeType.Created);
                }

                for (VFileEvent event : myDeleteCreateCopyChangeEvents) {
                    if (event instanceof VFileContentChangeEvent contentChangeEvent) {
                        addChange(client, contentChangeEvent.getFile(), FileChangeType.Changed);
                    }
                    if (event instanceof VFileCreateEvent createEvent) {
                        VirtualFile file = createEvent.getFile();
                        if (file != null) {
                            addChange(client, file, FileChangeType.Created);
                        }
                    }
                    if (event instanceof VFileCopyEvent copyEvent) {
                        VirtualFile file = copyEvent.findCreatedFile();
                        if (file != null) {
                            addChange(client, file, FileChangeType.Created);
                        }
                    }
                }
            });

            if (!myClientToFileChangeInfos.isEmpty()) {
                Application.get().executeOnPooledThread(() -> {
                    for (Map.Entry<LspClientImpl, Collection<FileChangeInfo>> entry : myClientToFileChangeInfos.entrySet()) {
                        entry.getKey().getWatchedFiles().processFileEvents(entry.getValue());
                    }
                });
            }

            // A file extension might have been changed.
            // A folder might have been moved out of the content root or vice versa.
            // So we need to check all opened and unsaved files and send `textDocument/didOpen` request to the server if needed.
            myClientsToUpdateOpenedFiles.forEach(it -> it.getDocumentSyncManager().openForOpenedOrUnsavedFiles());
        }
    }
}

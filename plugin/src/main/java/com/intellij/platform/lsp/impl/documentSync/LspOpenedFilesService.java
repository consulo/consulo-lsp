package com.intellij.platform.lsp.impl.documentSync;

import com.intellij.platform.lsp.api.LspBulkIntegrationProvider;
import com.intellij.platform.lsp.api.LspClientDescriptor;
import com.intellij.platform.lsp.api.LspIntegrationProvider;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.ReadAction;
import consulo.application.WriteAction;
import consulo.application.progress.ProgressManager;
import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.project.Project;
import consulo.ui.ModalityState;
import consulo.util.collection.MultiMap;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @see #processOpenedFiles
 * @see #scheduleClosingFilesThatAreNotOfInterest
 */
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
@Singleton
public class LspOpenedFilesService {
    public static LspOpenedFilesService getInstance(Project project) {
        return project.getInstance(LspOpenedFilesService.class);
    }

    private final Project myProject;

    // a linked map keeps the report order, so a batch processes the files in a predictable order;
    // the value is the start request stamp of the latest report, see LspClientManagerImpl.startRequestStamp
    private final Map<VirtualFile, Long> myOpenedFilesToHandle = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Object myOpenFilesCoalesceObject = new Object();
    private final Object myCloseFilesCoalesceObject = new Object();

    @Inject
    public LspOpenedFilesService(Project project) {
        myProject = project;
    }

    /**
     * Makes sure that the passed {@code files} are handled by the LSP servers that want to handle them. This means that:
     * <ul>
     * <li>for already running LSP servers that want to handle one or more files from the passed {@code files},
     * this function makes sure that the corresponding {@code textDocument/didOpen} requests have been sent to the server</li>
     * <li>if some servers want to handle one or more files from the passed {@code files}, but they haven't been started yet, they get started</li>
     * </ul>
     */
    public void processOpenedFiles(Collection<VirtualFile> files) {
        // Consulo has no TrustedProjects: every project is treated as trusted
        if (!LspIntegrationProvider.hasAnyExtensions()) {
            return;
        }

        // captured before any scheduling: a stop that comes after this report must win over the starts it requests
        long requestStamp = LspClientManagerImpl.getInstanceImpl(myProject).startRequestStamp();
        // a changed stamp needs a new batch too: the in-flight batch may carry the old stamp, and its starts are stale
        boolean changed = false;
        // there is no safe mode for untrusted files, so every file is treated as trusted
        for (VirtualFile file : files) {
            if (!Objects.equals(myOpenedFilesToHandle.put(file, requestStamp), requestStamp)) {
                changed = true;
            }
        }
        if (changed) {
            scheduleOpenedFilesProcessing();
        }
    }

    private record ClientToStart(
        Class<? extends LspIntegrationProvider> providerClass,
        LspClientDescriptor descriptor,
        long requestStamp
    ) {
    }

    private static final class OpenedFilesData {
        final Map<VirtualFile, Long> handledFiles = new LinkedHashMap<>();
        final MultiMap<LspClientImpl, VirtualFile> clientsToSendDidOpen = new MultiMap<>();
        final List<ClientToStart> newClientsToStart = new ArrayList<>();
    }

    private void scheduleOpenedFilesProcessing() {
        LspClientManagerImpl manager = LspClientManagerImpl.getInstanceImpl(myProject);

        ReadAction.nonBlocking(() -> {
                OpenedFilesData data = new OpenedFilesData();
                synchronized (myOpenedFilesToHandle) {
                    data.handledFiles.putAll(myOpenedFilesToHandle);
                }

                for (LspIntegrationProvider provider : LspIntegrationProvider.getAllExtensions()) {
                    Class<? extends LspIntegrationProvider> providerClass = provider.getClass();
                    Collection<LspClientImpl> clientsForProvider = manager.getClientsImpl(providerClass);

                    for (Map.Entry<VirtualFile, Long> entry : data.handledFiles.entrySet()) {
                        VirtualFile openedFile = entry.getKey();
                        long requestStamp = entry.getValue();
                        boolean fileWithinServerRootsAndSupported = false;
                        for (LspClientImpl lspClient : clientsForProvider) {
                            ProgressManager.checkCanceled();
                            if (isWithinRoots(lspClient.getDescriptor(), openedFile) && lspClient.isSupportedFile(openedFile)) {
                                fileWithinServerRootsAndSupported = true;
                            }

                            if (lspClient.getState() == LspServerState.Running &&
                                !lspClient.isFileOpened(openedFile) &&
                                lspClient.isSupportedFile(openedFile)) {
                                data.clientsToSendDidOpen.putValue(lspClient, openedFile);
                            }
                        }

                        if (!fileWithinServerRootsAndSupported /*&& ProjectFileIndex.getInstance(project).isInContent(openedFile)*/ ||
                            // a bulk provider may want one more client for a file that its other clients support
                            provider instanceof LspBulkIntegrationProvider) {
                            LspClientManagerImpl.LspStarterImpl starter = new LspClientManagerImpl.LspStarterImpl(provider);
                            provider.fileOpened(myProject, openedFile, starter);
                            for (LspClientDescriptor descriptor : starter.getDescriptors()) {
                                data.newClientsToStart.add(new ClientToStart(providerClass, descriptor, requestStamp));
                            }
                        }
                    }
                }

                return data;
            })
            .coalesceBy(myOpenFilesCoalesceObject)
            .expireWith(manager)
            .finishOnUiThread(application -> ModalityState.nonModal(), (OpenedFilesData data) -> {
                // a value-matched remove keeps an entry that a newer report re-stamped while the batch was computed
                data.handledFiles.forEach((file, requestStamp) -> myOpenedFilesToHandle.remove(file, requestStamp));
                if (!data.clientsToSendDidOpen.isEmpty()) {
                    WriteAction.run(() -> {
                        for (Map.Entry<LspClientImpl, Collection<VirtualFile>> entry : data.clientsToSendDidOpen.entrySet()) {
                            for (VirtualFile fileToOpen : entry.getValue()) {
                                entry.getKey().getDocumentSyncManager().open(fileToOpen);
                            }
                        }
                    });
                }
                for (ClientToStart it : data.newClientsToStart) {
                    manager.ensureStarted(it.providerClass(), it.descriptor(), it.requestStamp());
                }
            })
            .submit(AppExecutorUtil.getAppExecutorService());
    }

    private static boolean isWithinRoots(LspClientDescriptor descriptor, VirtualFile file) {
        for (VirtualFile root : descriptor.getRoots()) {
            if (VirtualFileUtil.isAncestor(root, file, true)) {
                return true;
            }
        }
        return false;
    }

    /**
     * For every running LSP server, sends {@code didClose} for files that are no longer open in the editor and are saved,
     * and for files that became excluded from the project.
     * The work is coalesced across calls, so it's cheap to invoke after any event that might have made some files irrelevant.
     */
    public void scheduleClosingFilesThatAreNotOfInterest() {
        LspClientManagerImpl lspClientManager = LspClientManagerImpl.getInstanceImpl(myProject);
        Collection<LspClientImpl> runningClients = lspClientManager.getRunningClients();
        if (runningClients.isEmpty()) {
            return;
        }

        ReadAction
            .nonBlocking(() -> {
                MultiMap<LspClientImpl, VirtualFile> clientToFilesToClose = new MultiMap<>();
                for (LspClientImpl client : runningClients) {
                    Collection<VirtualFile> filesToClose = client.getDocumentSyncManager().getFilesToClose();
                    if (!filesToClose.isEmpty()) {
                        clientToFilesToClose.putValues(client, filesToClose);
                    }
                }
                return clientToFilesToClose;
            })
            .expireWith(lspClientManager)
            .coalesceBy(myCloseFilesCoalesceObject)
            .finishOnUiThread(application -> ModalityState.nonModal(), (MultiMap<LspClientImpl, VirtualFile> clientToFilesToClose) -> {
                if (!clientToFilesToClose.isEmpty()) {
                    WriteAction.run(() -> {
                        for (Map.Entry<LspClientImpl, Collection<VirtualFile>> entry : clientToFilesToClose.entrySet()) {
                            for (VirtualFile file : entry.getValue()) {
                                entry.getKey().getDocumentSyncManager().close(file);
                            }
                        }
                    });
                }
            })
            .submit(AppExecutorUtil.getAppExecutorService());
    }
}

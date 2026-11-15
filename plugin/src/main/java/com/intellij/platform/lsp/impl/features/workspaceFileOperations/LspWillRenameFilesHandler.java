// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.workspaceFileOperations;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspDynamicCapabilities;
import com.intellij.platform.lsp.impl.platformListeners.LspRefactoringEventListener;
import com.intellij.platform.lsp.impl.util.LspWorkspaceEditApplier;
import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.application.Application;
import consulo.application.ReadAction;
import consulo.application.progress.ProgressManager;
import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.component.ProcessCanceledException;
import consulo.language.editor.refactoring.event.RefactoringEventData;
import consulo.language.psi.PsiDirectory;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiFileSystemItem;
import consulo.logging.Logger;
import consulo.lsp.impl.compat.RefactoringEventDataKeys;
import consulo.lsp.impl.compat.TasksKt;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.io.URLUtil;
import consulo.util.lang.ControlFlowException;
import consulo.util.lang.StringUtil;
import consulo.virtualFileSystem.StubVirtualFile;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.VirtualFileSystem;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.FileOperationFilter;
import org.eclipse.lsp4j.FileOperationOptions;
import org.eclipse.lsp4j.FileRename;
import org.eclipse.lsp4j.RenameFilesParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Upstream: {@code internal object LspWillRenameFilesHandler}.
 */
public final class LspWillRenameFilesHandler {
    private static final Logger LOG = Logger.getInstance(LspWillRenameFilesHandler.class);

    // The spec allows a client to drop a response that takes too long.
    private static final long WILL_RENAME_FILES_TIMEOUT_MS = 1000;

    private LspWillRenameFilesHandler() {
    }

    /**
     * Sends the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#workspace_willRenameFiles">workspace/willRenameFiles</a>
     * request before a Rename or Move refactoring changes the files, and applies the edits from the servers in the command of the refactoring.
     * <p>
     * Consulo: the planned renames are computed before the thread check (upstream: after it). Consulo fires
     * {@code refactoringStarted} inside the write action of the refactoring, and its before-data has no planned renames
     * ({@link RefactoringEventDataKeys}), so upstream's order would log the warning below for every Rename / Move.
     */
    public static void renameOrMoveRefactoringStarted(Project project, String refactoringId, RefactoringEventData beforeData) {
        List<LspClientImpl> lspClients = new ArrayList<>();
        for (LspClientImpl lspClient : LspClientManagerImpl.getInstanceImpl(project).getRunningClients()) {
            if (clientSupportsWillRename(lspClient.getClientCapabilities())) {
                lspClients.add(lspClient);
            }
        }
        if (lspClients.isEmpty()) {
            return;
        }

        List<LspPlannedRename> plannedRenames = switch (refactoringId) {
            case LspRefactoringEventListener.RENAME_REFACTORING_ID -> getPlannedRenamesFromRenameRefactoring(beforeData);
            case LspRefactoringEventListener.MOVE_REFACTORING_ID -> getPlannedRenamesFromMoveRefactoring(beforeData);
            default -> List.of();
        };
        if (plannedRenames.isEmpty()) {
            return;
        }

        Application application = Application.get();
        if (!application.isDispatchThread() || application.isWriteAccessAllowed()) {
            LOG.warn("Skipping workspace/willRenameFiles for '" + refactoringId + "': the event came " +
                (application.isDispatchThread() ? "on the EDT" : "off the EDT") + ", " +
                (application.isWriteAccessAllowed() ? "inside" : "outside") + " a write action. " +
                "Expected: EDT outside a write action.");
            return;
        }

        try {
            sendWillRenameFilesRequestsAndApplyEdits(project, lspClients, plannedRenames);
        }
        catch (Throwable e) {
            if (e instanceof ControlFlowException || e instanceof ProcessCanceledException) {
                throw e;
            }
            LOG.error(e);
        }
    }

    private static boolean clientSupportsWillRename(@Nullable ClientCapabilities clientCapabilities) {
        return clientCapabilities != null
            && clientCapabilities.getWorkspace() != null
            && clientCapabilities.getWorkspace().getFileOperations() != null
            && Boolean.TRUE.equals(clientCapabilities.getWorkspace().getFileOperations().getWillRename());
    }

    private static List<LspPlannedRename> getPlannedRenamesFromRenameRefactoring(RefactoringEventData beforeData) {
        Map<PsiElement, String> renames = beforeData.getUserData(RefactoringEventDataKeys.RENAMES_KEY);
        if (renames == null) {
            return List.of();
        }
        List<LspPlannedRename> result = new ArrayList<>();
        for (Map.Entry<PsiElement, String> entry : renames.entrySet()) {
            PsiElement element = entry.getKey();
            String newName = entry.getValue();
            if (!(element instanceof PsiFileSystemItem fileSystemItem) || !element.isValid() || fileSystemItem.getName().equals(newName)) {
                continue;
            }
            if (!(fileSystemItem.getParent() instanceof PsiDirectory parentDir)) {
                continue;
            }
            LspPlannedRename plannedRename = createPlannedRename(fileSystemItem, parentDir, newName);
            if (plannedRename != null) {
                result.add(plannedRename);
            }
        }
        return result;
    }

    private static List<LspPlannedRename> getPlannedRenamesFromMoveRefactoring(RefactoringEventData beforeData) {
        if (!(beforeData.getUserData(RefactoringEventDataKeys.TARGET_ELEMENT_KEY) instanceof PsiDirectory targetDir)) {
            return List.of();
        }
        PsiElement[] elements = beforeData.getUserData(RefactoringEventData.PSI_ELEMENT_ARRAY_KEY);
        if (elements == null) {
            return List.of();
        }
        List<LspPlannedRename> result = new ArrayList<>();
        for (PsiElement element : elements) {
            // the move skips an item that is already in the target, and a file whose name already exists there
            if (!(element instanceof PsiFileSystemItem fileSystemItem) || !element.isValid() || targetDir.equals(element.getParent())) {
                continue;
            }
            if (element instanceof PsiFile psiFile && targetDir.findFile(psiFile.getName()) != null) {
                continue;
            }
            LspPlannedRename plannedRename = createPlannedRename(fileSystemItem, targetDir, fileSystemItem.getName());
            if (plannedRename != null) {
                result.add(plannedRename);
            }
        }
        return result;
    }

    private static @Nullable LspPlannedRename createPlannedRename(PsiFileSystemItem fileSystemItem, PsiDirectory newParentDir, String newName) {
        VirtualFile fileOrDir = fileSystemItem.getVirtualFile();
        if (fileOrDir == null || !fileOrDir.isValid()) {
            return null;
        }
        return new LspPlannedRename(fileOrDir, newParentDir.getVirtualFile(), newName);
    }

    private static final class LspPlannedRename {
        private final VirtualFile myFileOrDir;
        private final VirtualFile myNewParentDir;
        private final String myNewName;

        private LspPlannedRename(VirtualFile fileOrDir, VirtualFile newParentDir, String newName) {
            myFileOrDir = fileOrDir;
            myNewParentDir = newParentDir;
            myNewName = newName;
        }

        public VirtualFile getFileOrDir() {
            return myFileOrDir;
        }

        public VirtualFile getNewParentDir() {
            return myNewParentDir;
        }

        public String getNewName() {
            return myNewName;
        }
    }

    private static final class ClientFilters {
        private final LspClientImpl myLspClient;
        private final List<FileOperationFilter> myFilters;

        private ClientFilters(LspClientImpl lspClient, List<FileOperationFilter> filters) {
            myLspClient = lspClient;
            myFilters = filters;
        }
    }

    /**
     * Consulo: {@code ApplicationManagerEx.runProcessWithProgressSynchronously(..., cancelText = "Skip")} - Consulo's modal progress
     * has no custom cancel button text, so the {@code lsp.will.rename.files.skip} text is not used.
     */
    @RequiredUIAccess
    private static void sendWillRenameFilesRequestsAndApplyEdits(
        Project project,
        List<LspClientImpl> lspClients,
        List<LspPlannedRename> plannedRenames
    ) {
        List<ClientFilters> lspClientToFilters = new ArrayList<>();
        for (LspClientImpl lspClient : lspClients) {
            List<FileOperationFilter> filters = getWillRenameFilters(lspClient);
            if (!filters.isEmpty()) {
                lspClientToFilters.add(new ClientFilters(lspClient, filters));
            }
        }
        if (lspClientToFilters.isEmpty()) {
            return;
        }

        List<LspWorkspaceEditApplier> workspaceEditAppliers = new ArrayList<>();
        boolean completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            () -> workspaceEditAppliers.addAll(sendWillRenameFilesRequests(lspClientToFilters, plannedRenames)),
            LspLocalize.lspWillRenameFilesProgressTitle(),
            true,
            project
        );

        if (completed) {
            for (LspWorkspaceEditApplier applier : workspaceEditAppliers) {
                applier.applyWorkspaceEdit();
            }
        }
    }

    private static List<FileOperationFilter> getWillRenameFilters(LspClientImpl lspClient) {
        List<FileOperationFilter> result = new ArrayList<>();
        ServerCapabilities serverCapabilities = lspClient.getServerCapabilities();
        if (serverCapabilities != null
            && serverCapabilities.getWorkspace() != null
            && serverCapabilities.getWorkspace().getFileOperations() != null
            && serverCapabilities.getWorkspace().getFileOperations().getWillRename() != null
            && serverCapabilities.getWorkspace().getFileOperations().getWillRename().getFilters() != null) {
            result.addAll(serverCapabilities.getWorkspace().getFileOperations().getWillRename().getFilters());
        }
        for (FileOperationOptions options : lspClient.getDynamicCapabilities()
            .getCapabilityRegistrationOptions(LspDynamicCapabilities.getWillRenameFiles())) {
            if (options.getFilters() != null) {
                result.addAll(options.getFilters());
            }
        }
        return result;
    }

    /**
     * Runs in a background thread. Upstream sends the requests in parallel coroutines and awaits all of them; here the requests are
     * sent at once and awaited one after another (a cancel of the progress cancels all of them).
     */
    private static List<LspWorkspaceEditApplier> sendWillRenameFilesRequests(
        List<ClientFilters> lspClientToFilters,
        List<LspPlannedRename> plannedRenames
    ) {
        List<LspClientImpl> clients = new ArrayList<>();
        List<CompletableFuture<@Nullable WorkspaceEdit>> futures = new ArrayList<>();
        for (ClientFilters clientFilters : lspClientToFilters) {
            List<LspPlannedRename> clientPlannedRenames = filterPlannedRenames(clientFilters.myLspClient, clientFilters.myFilters, plannedRenames);
            if (clientPlannedRenames.isEmpty()) {
                continue;
            }
            clients.add(clientFilters.myLspClient);
            futures.add(sendWillRenameFilesRequest(clientFilters.myLspClient, clientPlannedRenames));
        }

        List<LspWorkspaceEditApplier> result = new ArrayList<>();
        try {
            for (int i = 0; i < futures.size(); i++) {
                WorkspaceEdit edit = TasksKt.await(futures.get(i));
                if (edit == null) {
                    continue;
                }
                LspClientImpl lspClient = clients.get(i);
                LspWorkspaceEditApplier applier = ReadAction.compute(() -> LspWorkspaceEditApplier.create(lspClient, edit));
                if (applier != null) {
                    result.add(applier);
                }
            }
        }
        catch (ProcessCanceledException e) {
            for (CompletableFuture<@Nullable WorkspaceEdit> future : futures) {
                future.cancel(true);
            }
            throw e;
        }
        return result;
    }

    private static List<LspPlannedRename> filterPlannedRenames(
        LspClientImpl lspClient,
        List<FileOperationFilter> filters,
        List<LspPlannedRename> plannedRenames
    ) {
        List<LspPlannedRename> result = new ArrayList<>();
        for (LspPlannedRename plannedRename : plannedRenames) {
            String filePath = lspClient.getDescriptor().getFilePath(plannedRename.getFileOrDir());
            String uriScheme = plannedRename.getFileOrDir().getFileSystem().getProtocol();
            boolean isDirectory = plannedRename.getFileOrDir().isDirectory();

            boolean withinRoots = false;
            for (VirtualFile root : lspClient.getDescriptor().getRoots()) {
                if (VirtualFileUtil.isAncestor(root, plannedRename.getFileOrDir(), false)) {
                    withinRoots = true;
                    break;
                }
            }
            if (!withinRoots) {
                continue;
            }
            for (FileOperationFilter filter : filters) {
                if (lspClient.getGlobMatcher().pathMatchesFileOperationFilter(filePath, uriScheme, isDirectory, filter)) {
                    result.add(plannedRename);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Upstream: {@code suspend fun sendWillRenameFilesRequest(...)} with {@code withTimeoutOrNull(WILL_RENAME_FILES_TIMEOUT)}.
     * The returned future completes with {@code null} on timeout (the request is canceled) or on an error.
     */
    private static CompletableFuture<@Nullable WorkspaceEdit> sendWillRenameFilesRequest(LspClientImpl lspClient, List<LspPlannedRename> plannedRenames) {
        List<FileRename> fileRenames = new ArrayList<>();
        for (LspPlannedRename it : plannedRenames) {
            String oldUri = lspClient.getDescriptor().getFileUri(it.getFileOrDir());
            String newUri = lspClient.getDescriptor().getFileUri(
                new LspRenameTargetFile(it.getNewParentDir(), it.getNewName(), it.getFileOrDir().isDirectory())
            );
            fileRenames.add(new FileRename(oldUri, newUri));
        }
        RenameFilesParams params = new RenameFilesParams(fileRenames);

        CompletableFuture<@Nullable WorkspaceEdit> request = lspClient.sendRequest(server -> server.getWorkspaceService().willRenameFiles(params));
        // canceling the request future sends $/cancelRequest
        AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> request.cancel(true), WILL_RENAME_FILES_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        CompletableFuture<@Nullable WorkspaceEdit> result = request.handle((edit, e) -> {
            if (e == null) {
                return edit;
            }
            if (!(e instanceof CancellationException)) {
                // a failed server must not cancel the requests to other servers
                LOG.warn(lspClient.getDescriptor() + ": workspace/willRenameFiles request failed", e);
            }
            return null;
        });
        result.whenComplete((edit, e) -> {
            if (result.isCancelled()) {
                request.cancel(true);
            }
        });
        return result;
    }

    /**
     * The file or directory at the new location of a planned rename. It does not exist yet.
     * It has the file system and the path of the real file after the rename,
     * so {@link com.intellij.platform.lsp.api.LspClientDescriptor#getFileUri} and its overrides give the same URI for both.
     * <p>
     * Consulo: upstream extends {@code FakeVirtualFile}, which is internal in Consulo ({@code consulo.virtualFileSystem.internal});
     * its members are inlined here on top of {@link StubVirtualFile}.
     */
    private static final class LspRenameTargetFile extends StubVirtualFile {
        private final VirtualFile myParent;
        private final String myName;
        private final boolean myIsDirectory;

        private LspRenameTargetFile(VirtualFile newParentDir, String newName, boolean isDirectory) {
            myParent = newParentDir;
            myName = newName;
            myIsDirectory = isDirectory;
        }

        @Override
        public VirtualFile getParent() {
            return myParent;
        }

        @Override
        public String getName() {
            return myName;
        }

        @Override
        public String getPath() {
            String basePath = myParent.getPath();
            return StringUtil.endsWithChar(basePath, '/') ? basePath + myName : basePath + '/' + myName;
        }

        @Override
        public VirtualFileSystem getFileSystem() {
            return myParent.getFileSystem();
        }

        @Override
        public String getUrl() {
            return getFileSystem().getProtocol() + URLUtil.SCHEME_SEPARATOR + getPath();
        }

        @Override
        public boolean isDirectory() {
            return myIsDirectory;
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public String toString() {
            return getPath();
        }
    }
}

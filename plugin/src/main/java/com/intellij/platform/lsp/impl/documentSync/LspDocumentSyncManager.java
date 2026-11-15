package com.intellij.platform.lsp.impl.documentSync;

import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.impl.features.LspFeaturesRefreshing;
import com.intellij.platform.lsp.impl.features.highlighting.LspHighlightingApplier;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.access.RequiredWriteAction;
import consulo.application.Application;
import consulo.application.ReadAction;
import consulo.application.WriteAction;
import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.document.event.DocumentEvent;
import consulo.fileEditor.FileEditorManager;
import consulo.module.content.ProjectFileIndex;
import consulo.ui.ModalityState;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.SaveOptions;
import org.eclipse.lsp4j.TextDocumentSyncKind;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Sends document lifecycle notifications (didOpen, didChange, didSave, didClose) and tracks which files are open on the server.
 * <p>
 * The platform listeners fan out across running servers and dispatch into this class.
 */
public class LspDocumentSyncManager {
    private final LspClientImpl myClient;

    private final Set<VirtualFile> myOpenedFiles = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean myShutdown = new AtomicBoolean(false);

    /**
     * The version this client has last declared to the server for each file it has ever been asked about,
     * owned entirely by this class: {@link #nextDocumentVersion} advances it by exactly one per notification sent,
     * lazily starting a file at {@code 0} the first time it's queried -- callers are not required to route through
     * {@link #open} first, since not every document-sync path in this codebase does (some poll timestamps instead of
     * listening for document events). {@link #close}/{@link #shutdown} drop entries for files this instance's own open/close
     * lifecycle knows are done with, as a memory optimization only: correctness never depends on it, since a
     * fresh query for any file, closed or not, just restarts its own count from {@code 0}.
     * Reading a version adds no entry: only {@link #nextDocumentVersion} does.
     * <p>
     * This is deliberately independent of the document modification sequence,
     * which only promises to strictly increase on a text change, not by how much -- a delta some callers need
     * to predict before an edit is even applied (see {@link LspDidChangeUtil}).
     */
    private final Map<VirtualFile, AtomicInteger> myDocumentVersions = new ConcurrentHashMap<>();

    public LspDocumentSyncManager(LspClientImpl client) {
        myClient = client;
    }

    public int getOpenedFileCount() {
        return myOpenedFiles.size();
    }

    public boolean isFileOpened(VirtualFile file) {
        // Consulo has no BackedVirtualFile (notebook cell files), so the file is its own origin file
        return myOpenedFiles.contains(file);
    }

    public void forEachOpenedFile(Consumer<VirtualFile> action) {
        myOpenedFiles.forEach(action);
    }

    /**
     * The version last declared to the server for {@code file}.
     */
    public int currentDocumentVersion(VirtualFile file) {
        AtomicInteger version = myDocumentVersions.get(file);
        return version != null ? version.get() : 0;
    }

    /**
     * Advances and returns the version to declare for {@code file}'s next {@code didOpen}/{@code didChange}. Safe to call
     * regardless of when the underlying document mutation actually lands, since this owns the number outright.
     */
    public int nextDocumentVersion(VirtualFile file) {
        return myDocumentVersions.computeIfAbsent(file, f -> new AtomicInteger(0)).incrementAndGet();
    }

    public void shutdown() {
        myShutdown.set(true);
        myOpenedFiles.clear();
        myDocumentVersions.clear();
    }

    @RequiredWriteAction
    public void open(VirtualFile file) {
        if (myClient.getState() != LspServerState.Running) {
            String message = "Server is not in the Running state. Ignoring open(" + file + ")";
            // an open() scheduled before a server stop may land after it: the server is gone on purpose, not an error
            if (myClient.getState() == LspServerState.Initializing && !myShutdown.get()) {
                myClient.logError(message);
            }
            else {
                myClient.logInfo(message);
            }
            return;
        }

        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            myClient.logInfo("Skipping didOpen request because there's no document for file " + file);
            return;
        }

        if (myOpenedFiles.add(file)) {
            myClient.getDocumentMapping().getAdapterForFile(file).sendDidOpen(myClient, file, document);

            myClient.notifyFileOpened(file);
            // In the pull model the client is responsible for the initial pull of a just-opened file.
            // A pull-only server (no publishDiagnostics) would otherwise stay silent until the first daemon pass.
            LspHighlightingApplier.getInstance(myClient.getProject()).scheduleHighlightingRefresh(file);
            // upstream: application.messageBus.syncPublisher(BreadcrumbsXmlWrapper.FORCE_RELOAD_BREADCRUMBS).run()
            LspFeaturesRefreshing.refreshBreadcrumbs();
        }
        else {
            myClient.logError("open() cannot be called for already opened files. Ignoring: " + file);
        }
    }

    @RequiredWriteAction
    public void close(VirtualFile file) {
        if (!myOpenedFiles.remove(file) || myShutdown.get()) {
            // Error should not be logged if the sync manager is disposed of, as the server state
            // and thus sync manager state might have changed just after entering `open` method
            // and caller is not able to sync on the server state
            if (!myShutdown.get()) {
                myClient.logError("close() cannot be called for files that haven't been opened. Ignoring: " + file);
            }
            return;
        }
        myDocumentVersions.remove(file);
        myClient.fileClosed(file);

        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            // Document already gone — can only send a plain textDocument/didClose.
            // For notebook files this is incorrect (server expects notebookDocument/didClose),
            // but without the document we can't reconstruct cell structure.
            myClient.logWarn("No document for file " + file + " in close(), sending plain textDocument/didClose as fallback");
            DidCloseTextDocumentParams params = new DidCloseTextDocumentParams(myClient.getDocumentIdentifier(file));
            myClient.getRequestExecutor().sendNotification(it -> it.getTextDocumentService().didClose(params));
            return;
        }
        myClient.getDocumentMapping().getAdapterForFile(file).sendDidClose(myClient, file, document);
    }

    /**
     * Iterates all files that are either unsaved or opened in the editor, and sends {@code textDocument/didOpen} to the server if needed.
     */
    public void openForOpenedOrUnsavedFiles() {
        ReadAction
            .nonBlocking(() -> {
                Set<VirtualFile> openedAndUnsavedFiles = new HashSet<>();
                for (VirtualFile file : FileEditorManager.getInstance(myClient.getProject()).getOpenFiles()) {
                    if (!myOpenedFiles.contains(file) && myClient.isSupportedFile(file)) {
                        openedAndUnsavedFiles.add(file);
                    }
                }
                for (Document document : FileDocumentManager.getInstance().getUnsavedDocuments()) {
                    VirtualFile file = FileDocumentManager.getInstance().getFile(document);
                    if (file != null && !myOpenedFiles.contains(file) && myClient.isSupportedFile(file)) {
                        openedAndUnsavedFiles.add(file);
                    }
                }
                return openedAndUnsavedFiles;
            })
            .expireWhen(() -> myClient.getState() != LspServerState.Running)
            .finishOnUiThread(application -> ModalityState.nonModal(), (Set<VirtualFile> files) -> {
                if (files.isEmpty()) {
                    return;
                }
                WriteAction.run(() -> {
                    myClient.logDebug("Opening files after server initialization or after move/rename: " + files);
                    files.forEach(this::open);
                });
            })
            .submit(AppExecutorUtil.getAppExecutorService());
    }

    /**
     * @return files that have been opened by this server (with {@code didOpen} request), but now they are neither opened in the editor nor unsaved.
     * Also the files that became excluded from the project, even when they are still open in the editor.
     * Must be called on a background thread.
     */
    @RequiredReadAction
    public Collection<VirtualFile> getFilesToClose() {
        FileEditorManager fileEditorManager = FileEditorManager.getInstance(myClient.getProject());
        FileDocumentManager fileDocumentManager = FileDocumentManager.getInstance();

        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(myClient.getProject());

        List<VirtualFile> result = new ArrayList<>();
        for (VirtualFile file : myOpenedFiles) {
            // Consulo has no BackedVirtualFile, so the file is its own origin file
            VirtualFile originFile = file;
            // `didOpen` is never sent for an excluded file, so a file that became excluded is closed the same way
            if (originFile.isInLocalFileSystem() && fileIndex.isExcluded(originFile)) {
                result.add(file);
                continue;
            }
            if (fileEditorManager.isFileOpen(originFile)) {
                continue;
            }

            Document cachedDoc = fileDocumentManager.getCachedDocument(originFile);
            boolean isSaved = cachedDoc == null || !fileDocumentManager.isDocumentUnsaved(cachedDoc);
            if (isSaved) {
                result.add(file);
            }
        }
        return result;
    }

    /**
     * Schedules a {@code textDocument/didSave} notification once the in-progress write action that triggered the save completes.
     */
    public void scheduleSave(VirtualFile file, Document document) {
        SaveOptions didSaveOptions = LspServerCapabilitiesKt.getDidSaveOptions(myClient, file);
        if (didSaveOptions == null) {
            return;
        }
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            if (myClient.getProject().isDisposed()) {
                return;
            }
            // Using a read action guarantees that the write action in which calling `beforeDocumentSaving()` was called has finished,
            // so the file has been physically saved, therefore it's now good time to send `textDocument/didSave`
            Application.get().runReadAction(() -> {
                myClient.getDocumentMapping().getAdapterForFile(file)
                    .sendDidSave(myClient, file, document, Boolean.TRUE.equals(didSaveOptions.getIncludeText()));
            });
        });
    }

    /**
     * Per-server handler for {@link consulo.document.event.DocumentListener#beforeDocumentChange}.
     * <p>
     * Incremental {@code DidChangeTextDocumentParams} must be created *before* the document mutation, because the params
     * encode line numbers as they were before the change.
     */
    @RequiredUIAccess
    public void onBeforeDocumentChange(DocumentEvent event, VirtualFile file) {
        myClient.fileEdited(file, event);

        if (LspServerCapabilitiesKt.getTextDocumentSyncKind(myClient) == TextDocumentSyncKind.Incremental && isFileOpened(file)) {
            myClient.getDocumentMapping().getAdapterForFile(file).sendDidChangeIncremental(myClient, file, event);
        }
    }

    /**
     * Per-server handler for {@link consulo.document.event.DocumentListener#documentChanged}.
     * <p>
     * Full {@code DidChangeTextDocumentParams} can only be calculated *after* the document mutation, because it needs the new text.
     */
    @RequiredUIAccess
    public void onDocumentChanged(DocumentEvent event, VirtualFile file) {
        if (LspServerCapabilitiesKt.getTextDocumentSyncKind(myClient) == TextDocumentSyncKind.Full && isFileOpened(file)) {
            myClient.getDocumentMapping().getAdapterForFile(file).sendDidChangeFull(myClient, file, event.getDocument());
        }
    }
}

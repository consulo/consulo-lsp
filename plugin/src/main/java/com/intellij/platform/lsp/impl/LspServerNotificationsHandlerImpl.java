package com.intellij.platform.lsp.impl;

import com.intellij.platform.lsp.api.LspServerNotificationsHandler;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.platform.lsp.impl.features.LspFeaturesRefreshing;
import com.intellij.platform.lsp.impl.util.LspWorkspaceEditApplier;
import com.intellij.platform.lsp.localize.LspLocalize;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.application.Application;
import consulo.application.ReadAction;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.Task;
import consulo.codeEditor.Editor;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.fileEditor.FileEditor;
import consulo.fileEditor.FileEditorManager;
import consulo.fileEditor.TextEditor;
import consulo.language.editor.DaemonCodeAnalyzer;
import consulo.language.editor.WriteCommandAction;
import consulo.language.editor.folding.CodeFoldingManager;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.navigation.OpenFileDescriptor;
import consulo.navigation.OpenFileDescriptorFactory;
import consulo.process.ProcessOutputTypes;
import consulo.process.util.AnsiEscapeDecoder;
import consulo.project.Project;
import consulo.project.ui.notification.Notification;
import consulo.project.ui.notification.NotificationDisplayType;
import consulo.project.ui.notification.NotificationGroup;
import consulo.project.ui.notification.NotificationType;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnAction;
import consulo.ui.ex.action.AnActionEvent;
import consulo.virtualFileSystem.VirtualFile;
import consulo.webBrowser.BrowserUtil;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.ConfigurationItem;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.LogTraceParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ShowDocumentParams;
import org.eclipse.lsp4j.ShowDocumentResult;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentContentRefreshParams;
import org.eclipse.lsp4j.Unregistration;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCancelParams;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressNotification;
import org.eclipse.lsp4j.WorkDoneProgressReport;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class LspServerNotificationsHandlerImpl implements LspServerNotificationsHandler {
    private static final Logger LOG = Logger.getInstance(LspServerNotificationsHandlerImpl.class);

    /**
     * Default behavior: show balloon and write to the Notifications tool window.
     * The value of this string is the id of {@link #SHOW_MESSAGE_GROUP}, registered by {@link LspNotificationGroupContributor}.
     */
    public static final String SHOW_MESSAGE_NOTIFICATION_GROUP = "LSP window/showMessage";

    /**
     * Upstream: the {@code notificationGroup} registration in {@code intellij.platform.lsp.impl.xml}.
     */
    public static final NotificationGroup SHOW_MESSAGE_GROUP = new NotificationGroup(
        SHOW_MESSAGE_NOTIFICATION_GROUP,
        LspLocalize.notificationGroupLspMessage(),
        NotificationDisplayType.BALLOON,
        true
    );

    private final LspClientImpl myLspClient;
    private final Project myProject;

    private record ProgressTask(String text, @Nullable String details, @Nullable Double fraction) {
        ProgressTask copy(@Nullable String details, @Nullable Double fraction) {
            return new ProgressTask(text, details, fraction);
        }
    }

    /**
     * The replacement of the upstream progress coroutine {@code Job}: cancels the background task of one progress token.
     */
    private static final class ProgressJob {
        private volatile boolean myCancelled;
        private volatile @Nullable ProgressIndicator myIndicator;

        void cancel() {
            myCancelled = true;
            ProgressIndicator indicator = myIndicator;
            if (indicator != null) {
                indicator.cancel();
            }
        }

        boolean isCancelled() {
            return myCancelled;
        }

        void setIndicator(ProgressIndicator indicator) {
            myIndicator = indicator;
            if (myCancelled) {
                indicator.cancel();
            }
        }
    }

    private final ConcurrentHashMap<String, ProgressTask> myProgressTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProgressJob> myProgressJobs = new ConcurrentHashMap<>();

    /**
     * The responses to the {@code window/showMessageRequest} requests that the user has not answered yet.
     * The Notifications tool window can keep a notification for a long time. Through the response, lsp4j holds the old client.
     * So {@link #releasePendingMessageRequests} clears these references when the client stops.
     */
    private final Set<AtomicReference<@Nullable CompletableFuture<MessageActionItem>>> myPendingMessageRequests = ConcurrentHashMap.newKeySet();

    private final AnsiEscapeDecoder myAnsiDecoder = new AnsiEscapeDecoder();

    /**
     * When set (by {@link com.intellij.platform.lsp.impl.features.LspExecuteCommandKt#executeCommandExpectingWorkspaceEdit}), incoming
     * {@code workspace/applyEdit} requests are handled by this handler instead of being applied in the usual way.
     * Written and read on different threads, hence {@code volatile}.
     */
    private volatile @Nullable Consumer<WorkspaceEdit> myNextApplyEditHandler;

    public LspServerNotificationsHandlerImpl(LspClientImpl lspClient) {
        myLspClient = lspClient;
        myProject = lspClient.getProject();
    }

    public Project getProject() {
        return myProject;
    }

    public @Nullable Consumer<WorkspaceEdit> getNextApplyEditHandler() {
        return myNextApplyEditHandler;
    }

    public void setNextApplyEditHandler(@Nullable Consumer<WorkspaceEdit> nextApplyEditHandler) {
        myNextApplyEditHandler = nextApplyEditHandler;
    }

    @Override
    public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
        Consumer<WorkspaceEdit> handler = myNextApplyEditHandler;
        if (handler != null) {
            myNextApplyEditHandler = null;
            handler.accept(params.getEdit());
            // a bit of cheating here: at this moment the edit is not yet applied; there are no known issues because of that
            return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(true));
        }

        CompletableFuture<ApplyWorkspaceEditResponse> future = new CompletableFuture<>();

        // upstream: readAndEdtWriteAction { ... writeCommandAction(project, commandName) { ... } } in the LspClientManagerImpl scope
        Application.get().invokeLater(() -> {
            try {
                if (myProject.isDisposed()) {
                    return;
                }
                LspWorkspaceEditApplier applier = ReadAction.compute(() -> LspWorkspaceEditApplier.create(myLspClient, params.getEdit()));
                if (applier == null) {
                    return;
                }
                String commandName = params.getLabel() != null
                    ? params.getLabel()
                    : LspLocalize.codeChangeFromServer(myLspClient.getDescriptor().getPresentableName()).get();
                WriteCommandAction.writeCommandAction(myProject).withName(commandName).run(() -> {
                    applier.applyWorkspaceEdit();
                    future.complete(new ApplyWorkspaceEditResponse(true));
                });
            }
            finally {
                if (!future.isDone()) {
                    future.complete(new ApplyWorkspaceEditResponse(false));
                }
            }
        });

        return future;
    }

    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
        List<String> methods = new ArrayList<>();
        for (Registration registration : params.getRegistrations()) {
            myLspClient.getDynamicCapabilities().registerCapability(registration);
            methods.add(registration.getMethod());
        }
        restartHighlightingIfNeeded(methods);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
        for (Unregistration unregistration : params.getUnregisterations()) {
            myLspClient.getDynamicCapabilities().unregisterCapability(unregistration);
        }
        return CompletableFuture.completedFuture(null);
    }

    private void restartHighlightingIfNeeded(List<String> registeredCapabilities) {
        boolean needsInlayHintRefresh = registeredCapabilities.stream().anyMatch(it ->
            it.equals(LspDynamicCapabilities.getInlayHint().getFirst()) ||
                it.equals(LspDynamicCapabilities.getDocumentColor().getFirst())
        );

        boolean needsFoldingUpdate = registeredCapabilities.stream().anyMatch(it ->
            it.equals(LspDynamicCapabilities.getFoldingRange().getFirst())
        );

        boolean needsDaemonRestart = registeredCapabilities.stream().anyMatch(it ->
            it.equals(LspDynamicCapabilities.getDocumentLink().getFirst()) ||
                it.equals(LspDynamicCapabilities.getDiagnostic().getFirst()) ||
                it.equals(LspDynamicCapabilities.getDocumentHighlight().getFirst())
        );

        // A registration that replaces an earlier one — servers re-register after re-reading their configuration — means
        // the diagnostics pulled through the previous one are stale. Restarting the daemon alone doesn't re-request them:
        // the cache is only considered stale when the PSI changed, which it didn't.
        boolean needsDiagnosticsRefresh = registeredCapabilities.stream().anyMatch(it ->
            it.equals(LspDynamicCapabilities.getDiagnostic().getFirst())
        );

        boolean needsCodeLensesRefresh = registeredCapabilities.stream().anyMatch(it ->
            it.equals(LspDynamicCapabilities.getCodeLens().getFirst())
        );

        if (needsInlayHintRefresh) {
            myLspClient.refreshInlayHints();
        }

        if (needsDiagnosticsRefresh) {
            myLspClient.refreshDiagnostics();
        }

        if (needsCodeLensesRefresh) {
            LspFeaturesRefreshing.refreshCodeLenses(myProject);
        }

        if (needsFoldingUpdate) {
            // Consulo: FileEditorManager.getAllEditors() requires read access
            FileEditor[] allEditors = ReadAction.compute(() -> FileEditorManager.getInstance(myProject).getAllEditors());
            for (FileEditor fileEditor : allEditors) {
                if (fileEditor instanceof TextEditor textEditor) {
                    // Also calls `DaemonCodeAnalyzer.restart` internally
                    CodeFoldingManager.getInstance(myProject).scheduleAsyncFoldingUpdate(textEditor.getEditor());
                }
            }
        }

        // Folding update already restarts the daemon internally, so only restart explicitly when it wasn't triggered.
        if (needsDaemonRestart && !needsFoldingUpdate) {
            DaemonCodeAnalyzer.getInstance(myProject).restart("LspClientManagerImpl.registerCapabilities");
        }
    }

    @Override
    public void telemetryEvent(Object object) {
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams params) {
        if (!myProject.isDisposed()) {
            myLspClient.diagnosticsReceived(params);
        }
    }

    @Override
    public CompletableFuture<ShowDocumentResult> showDocument(ShowDocumentParams params) {
        String uri = params.getUri();

        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            BrowserUtil.browse(uri);
            return CompletableFuture.completedFuture(new ShowDocumentResult(true));
        }

        // the target can be a dynamic file, whose text needs a server request: never wait for it on the LSP message thread.
        CompletableFuture<ShowDocumentResult> result = new CompletableFuture<>();
        LspCoroutineScopeService scope = LspCoroutineScopeService.getInstance(myProject);
        if (scope.isDisposed()) {
            return CompletableFuture.completedFuture(new ShowDocumentResult(false));
        }
        scope.launch(() -> {
            try {
                if (result.isDone()) {
                    // A server `$/cancelRequest` cancels the returned future
                    return;
                }
                VirtualFile file = myLspClient.getDynamicFiles().findTargetFile(uri);
                if (file == null) {
                    result.complete(new ShowDocumentResult(false));
                    return;
                }
                Application.get().invokeLater(() -> {
                    try {
                        if (result.isDone()) {
                            return;
                        }
                        boolean opened = openFile(file, !Boolean.FALSE.equals(params.getTakeFocus()), params.getSelection());
                        result.complete(new ShowDocumentResult(opened));
                    }
                    catch (Throwable t) {
                        result.completeExceptionally(t);
                    }
                }, () -> {
                    if (myProject.isDisposed()) {
                        result.complete(new ShowDocumentResult(false));
                        return true;
                    }
                    return false;
                });
            }
            catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    /**
     * Opens {@code file} in an editor and selects {@code selection}. Returns {@code false} when no text editor opens.
     */
    @RequiredUIAccess
    private boolean openFile(VirtualFile file, boolean focusEditor, @Nullable Range selection) {
        if (!file.isValid()) {
            return false;
        }

        if (selection != null) {
            Document document = FileDocumentManager.getInstance().getDocument(file);
            if (document != null) {
                Integer startOffset = Lsp4jUtilKt.getOffsetInDocument(document, selection.getStart());
                Integer endOffset = Lsp4jUtilKt.getOffsetInDocument(document, selection.getEnd());
                if (startOffset != null && endOffset != null) {
                    OpenFileDescriptor descriptor = OpenFileDescriptorFactory.getInstance(myProject).newBuilder(file).offset(startOffset).build();
                    Editor editor = FileEditorManager.getInstance(myProject).openTextEditor(descriptor, focusEditor);
                    if (editor != null && !startOffset.equals(endOffset)) {
                        editor.getSelectionModel().setSelection(startOffset, endOffset);
                    }
                    return editor != null;
                }
            }
        }

        OpenFileDescriptor descriptor = OpenFileDescriptorFactory.getInstance(myProject).newBuilder(file).build();
        return FileEditorManager.getInstance(myProject).openTextEditor(descriptor, focusEditor) != null;
    }

    @Override
    public CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
        if (myProject.isDisposed()) {
            return CompletableFuture.completedFuture(List.of());
        }

        List<WorkspaceFolder> folders = new ArrayList<>();
        for (VirtualFile root : myLspClient.getDescriptor().getRoots()) {
            folders.add(new WorkspaceFolder(myLspClient.getDescriptor().getFileUri(root), root.getName()));
        }
        return CompletableFuture.completedFuture(folders);
    }

    @Override
    public CompletableFuture<List<@Nullable Object>> configuration(ConfigurationParams params) {
        if (myProject.isDisposed()) {
            return CompletableFuture.completedFuture(List.of());
        }

        List<@Nullable Object> result = new ArrayList<>();
        for (ConfigurationItem item : params.getItems()) {
            result.add(myLspClient.getDescriptor().getWorkspaceConfiguration(item));
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
        return CompletableFuture.completedFuture(null);
    }

    private static @Nullable Double toFraction(@Nullable Integer percentage) {
        return percentage != null ? Math.max(0.0, Math.min(1.0, percentage / 100.0)) : null;
    }

    private boolean isShutdown() {
        return myLspClient.getState() == LspServerState.ShutdownNormally || myLspClient.getState() == LspServerState.ShutdownUnexpectedly;
    }

    @Override
    public void notifyProgress(ProgressParams params) {
        if (myProject.isDisposed()) {
            return;
        }

        Either<String, Integer> token = params.getToken();
        String tokenId = token.map(it -> it, Object::toString);

        WorkDoneProgressNotification value = params.getValue().getLeft();
        if (value instanceof WorkDoneProgressBegin begin) {
            ProgressJob previousJob = myProgressJobs.get(tokenId);
            if (previousJob != null) {
                previousJob.cancel();
            }

            // A Begin after the server stopped must not start an indicator: End will never arrive,
            // and cancelAllProgress() has already run, so nothing would ever stop the indicator
            if (isShutdown()) {
                return;
            }

            ProgressJob job = new ProgressJob();
            boolean cancellable = Boolean.TRUE.equals(begin.getCancellable());

            myProgressTasks.put(tokenId, new ProgressTask(begin.getTitle(), begin.getMessage(), toFraction(begin.getPercentage())));

            // upstream: withBackgroundProgress(project, title, cancellable) { reportRawProgress { ... } } in the LspClientManagerImpl scope
            Task.Backgroundable task = new Task.Backgroundable(
                myProject,
                LspLocalize.progressTitleProgress(myLspClient.getDescriptor().getPresentableName()),
                cancellable
            ) {
                @Override
                public void run(ProgressIndicator indicator) {
                    job.setIndicator(indicator);
                    indicator.setIndeterminate(true);
                    try {
                        while (!job.isCancelled() && !indicator.isCanceled()) {
                            ProgressTask currentState = myProgressTasks.get(tokenId);
                            if (currentState == null) {
                                break;
                            }
                            Double fraction = currentState.fraction();
                            if (fraction != null) {
                                indicator.setIndeterminate(false);
                                indicator.setFraction(fraction);
                            }
                            else {
                                indicator.setIndeterminate(true);
                            }
                            indicator.setText(currentState.text());
                            indicator.setText2(currentState.details() != null ? currentState.details() : "");
                            try {
                                Thread.sleep(100);
                            }
                            catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                    finally {
                        // A cancellation while the server is running means the user cancelled the indicator, so tell the server.
                        // A cancellation after the server stopped comes from cancelAllProgress(); there is no server to notify anymore.
                        // A cancellation by `End` (or a new `Begin` for the token) comes from this handler, not from the user.
                        if (indicator.isCanceled() && !job.isCancelled() && cancellable && myLspClient.getState() == LspServerState.Running) {
                            myLspClient.sendNotification(it -> it.cancelProgress(new WorkDoneProgressCancelParams(token)));
                        }
                        myProgressJobs.remove(tokenId, job);
                    }
                }
            };

            myProgressJobs.put(tokenId, job);
            task.queue();

            // The server may have stopped between the check above and this registration;
            // cancelAllProgress() could have run in between and missed this job
            if (isShutdown()) {
                myProgressTasks.remove(tokenId);
                ProgressJob removed = myProgressJobs.remove(tokenId);
                if (removed != null) {
                    removed.cancel();
                }
            }
        }
        else if (value instanceof WorkDoneProgressReport report) {
            myProgressTasks.computeIfPresent(tokenId, (key, currentState) -> {
                Double fraction = toFraction(report.getPercentage());
                return currentState.copy(
                    report.getMessage() != null ? report.getMessage() : currentState.details(),
                    fraction != null ? fraction : currentState.fraction()
                );
            });
        }
        else if (value instanceof WorkDoneProgressEnd) {
            myProgressTasks.remove(tokenId);
            ProgressJob removed = myProgressJobs.remove(tokenId);
            if (removed != null) {
                removed.cancel();
            }
        }
    }

    /**
     * Cancels every in-flight progress indicator started by the server. Called when the server stops so its background
     * progresses don't keep running. The state is already set to shutdown by then, so the completion handler in
     * {@link #notifyProgress} won't send a {@code window/workDoneProgress/cancel} back to the server that is going away.
     */
    public void cancelAllProgress() {
        myProgressTasks.clear();
        myProgressJobs.values().forEach(ProgressJob::cancel);
        myProgressJobs.clear();
    }

    /**
     * Drops the responses to the unanswered {@code window/showMessageRequest} requests. The server is gone and cannot get them.
     */
    public void releasePendingMessageRequests() {
        for (AtomicReference<@Nullable CompletableFuture<MessageActionItem>> it : myPendingMessageRequests) {
            it.set(null);
        }
        myPendingMessageRequests.clear();
    }

    @Override
    public CompletableFuture<Void> refreshSemanticTokens() {
        if (!myProject.isDisposed()) {
            myLspClient.refreshSemanticTokens();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> refreshCodeLenses() {
        LspFeaturesRefreshing.refreshCodeLenses(myProject);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> refreshInlayHints() {
        if (!myProject.isDisposed()) {
            myLspClient.refreshInlayHints();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> refreshInlineValues() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> refreshDiagnostics() {
        if (!myProject.isDisposed()) {
            myLspClient.refreshDiagnostics();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> refreshTextDocumentContent(TextDocumentContentRefreshParams params) {
        if (!myProject.isDisposed()) {
            myLspClient.getDynamicFiles().refreshContent(params.getUri());
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
        if (myProject.isDisposed()) {
            return CompletableFuture.completedFuture(null);
        }

        String actions = params.getActions() != null
            ? params.getActions().stream().map(MessageActionItem::getTitle).collect(Collectors.joining(", "))
            : "null";
        myLspClient.logInfo("window/showMessageRequest: " + params.getMessage() + ": " + actions);
        // TODO serviceView stage: serviceViewConsole()?.printShowMessage(params.type, "${params.message}: ...")
        return doNotify(params.getMessage(), getNotificationType(params), SHOW_MESSAGE_GROUP, params.getActions());
    }

    @Override
    public void showMessage(MessageParams params) {
        if (myProject.isDisposed()) {
            return;
        }

        myLspClient.logInfo("window/showMessage: " + params.getMessage());
        // TODO serviceView stage: serviceViewConsole()?.printShowMessage(params.type, params.message)
        doNotify(params.getMessage(), getNotificationType(params), SHOW_MESSAGE_GROUP, null);
    }

    @Override
    public void logMessage(MessageParams params) {
        if (myProject.isDisposed()) {
            return;
        }

        myLspClient.logInfo("window/logMessage " + params.getType() + ": " + params.getMessage());
        // TODO serviceView stage: serviceViewConsole()?.printLogMessage(params.type, params.message)
    }

    @Override
    public void logTrace(LogTraceParams params) {
        if (myProject.isDisposed()) {
            return;
        }

        // no need to LOG.info() it additionally; LOG.debug() done in Lsp4jServerConnector.createMessageJsonHandler is enough.
        String message = params.getVerbose() != null ? params.getMessage() + "\n" + params.getVerbose() : params.getMessage();
        // TODO serviceView stage: serviceViewConsole()?.printTrace(message)
        LOG.debug(message);
    }

    private static NotificationType getNotificationType(MessageParams params) {
        return switch (params.getType()) {
            case Error -> NotificationType.ERROR;
            case Warning -> NotificationType.WARNING;
            default -> NotificationType.INFORMATION;
        };
    }

    private CompletableFuture<MessageActionItem> doNotify(
        String message,
        NotificationType type,
        NotificationGroup notificationGroup,
        @Nullable List<MessageActionItem> actionItems
    ) {
        CompletableFuture<MessageActionItem> result = new CompletableFuture<>();

        StringBuilder cleanedMessage = new StringBuilder();
        // STDOUT - is the most appropriate option as we are processing notification messages (which are typically like stdout).
        myAnsiDecoder.escapeText(message, ProcessOutputTypes.STDOUT, (text, attributes) -> cleanedMessage.append(text));

        String presentableMessage = myLspClient.getDescriptor().getPresentableName() + ": " + cleanedMessage;
        // The actions use only local values. A reference to this handler would keep the old client.
        AtomicReference<@Nullable CompletableFuture<MessageActionItem>> pendingResult = new AtomicReference<>(result);
        Set<AtomicReference<@Nullable CompletableFuture<MessageActionItem>>> pendingRequests = myPendingMessageRequests;
        if (actionItems != null && !actionItems.isEmpty()) {
            pendingRequests.add(pendingResult);
            // releasePendingMessageRequests() could have run before the add and missed this request
            if (isShutdown()) {
                releasePendingMessageRequests();
            }
        }
        Notification notification = notificationGroup.createNotification(presentableMessage, type);
        if (actionItems != null) {
            for (MessageActionItem actionItem : actionItems) {
                String actionLabel = actionItem.getTitle();
                notification.addAction(new AnAction(LocalizeValue.of(actionLabel)) {
                    @Override
                    @RequiredUIAccess
                    public void actionPerformed(AnActionEvent e) {
                        notification.expire();
                        pendingRequests.remove(pendingResult);
                        CompletableFuture<MessageActionItem> pending = pendingResult.getAndSet(null);
                        if (pending != null) {
                            pending.complete(actionItem);
                        }
                    }
                });
            }
        }
        if (myLspClient.getDescriptor().getOfferServerLogInNotifications() && (type == NotificationType.ERROR || type == NotificationType.WARNING)) {
            // TODO serviceView stage: notification.addAction(ShowLspServerLogAction(lspClient))
        }
        notification.notify(myProject);

        return result;
    }
}

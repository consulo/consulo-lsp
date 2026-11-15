// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
/*
 * Copyright 2013-2026 consulo.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package consulo.lsp.impl.compat;

import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.annotation.access.RequiredReadAction;
import consulo.application.Application;
import consulo.application.WriteAction;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.ProgressManager;
import consulo.application.progress.Task;
import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.component.ProcessCanceledException;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.document.util.TextRange;
import consulo.language.codeStyle.CodeStyle;
import consulo.language.codeStyle.ExternalFormatProcessor;
import consulo.language.codeStyle.FormattingContext;
import consulo.language.codeStyle.FormattingMode;
import consulo.language.psi.PsiDocumentManager;
import consulo.language.psi.PsiFile;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationGroup;
import consulo.project.ui.notification.NotificationService;
import consulo.undoRedo.CommandProcessor;
import consulo.util.dataholder.Key;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * An asynchronous document formatting service on top of {@link ExternalFormatProcessor}, because there is no
 * formatting service extension point.
 * <p>
 * Extend this class if there is a long-lasting formatting operation which may block the UI thread. The actual formatting code is placed
 * then in {@link FormattingTask#run()} method which may be slow.
 * <p>
 * If another {@code formatDocument()} call is made for the same document, the previous request is cancelled. On success, if
 * {@code cancel()} returns {@code true}, another request replaces the previous one. Otherwise, the newer request is rejected.
 * <p>
 * Before the actual formatting starts, {@link #createFormattingTask(AsyncFormattingRequest)} method is called. It should be fast enough
 * not to block the UI thread. If it succeeds (doesn't return null), further formatting is started using the created runnable on a
 * separate thread.
 * <p>
 * Consulo bridge: {@link #activeForFile} is {@code true} when the service can format the whole file or (with
 * {@link Feature#FORMAT_FRAGMENTS}) a fragment of it; {@link #format} starts {@link #formatDocument} for the range and returns the range
 * as is (the formatted text is applied later). {@link AsyncDocumentFormattingPostFormatProcessor} routes
 * {@code CodeStyleManager.reformatText} to {@link #format}, which Consulo's {@code CodeStyleManagerImpl} leaves to post-processors when
 * an external formatter is active. Not ported: {@code DocumentMerger} (a result for a changed document is dropped), the temporary
 * {@code getIOFile()}, timeout actions, the structured (awaitable) formatting scope.
 */
public abstract class AsyncDocumentFormattingService implements FormattingService, ExternalFormatProcessor {
    private static final Logger LOG = Logger.getInstance(AsyncDocumentFormattingService.class);

    public static final Key<Boolean> FORMAT_DOCUMENT_SYNCHRONOUSLY =
        Key.create("com.intellij.formatting.service.AsyncDocumentFormattingService.FORMAT_DOCUMENT_SYNCHRONOUSLY");

    protected static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final Map<Document, FormattingRequestImpl> myPendingRequests = new ConcurrentHashMap<>();

    /**
     * Called before the actual formatting starts.
     *
     * @param formattingRequest The formatting request to create the formatting task for.
     * @return {@link FormattingTask} if successful and formatting can proceed, {@code null} otherwise. The latter may be a result, for
     * example, of misconfiguration.
     */
    protected abstract @Nullable FormattingTask createFormattingTask(AsyncFormattingRequest formattingRequest);

    /**
     * @return A notification group to use when error messages are shown to an end user.
     */
    protected abstract NotificationGroup getNotificationGroup();

    protected boolean needToUpdate() {
        return true;
    }

    /**
     * @return A name which can be used in UI, for example, in notification messages.
     */
    protected abstract LocalizeValue getName();

    /**
     * Hook called before creating a formatting request. Default implementation saves the document
     * to ensure external formatters working with IO files see the latest content.
     */
    protected void prepareForFormatting(Document document, FormattingContext formattingContext) {
        FileDocumentManager.getInstance().saveDocument(document);
    }

    /**
     * @return A duration to wait for the service to respond (call either {@code onTextReady()} or {@code onError()}).
     */
    protected Duration getTimeout() {
        return DEFAULT_TIMEOUT;
    }

    public interface FormattingTask extends Runnable {
        /**
         * Cancel the current runnable.
         *
         * @return {@code true} if the runnable has been successfully cancelled, {@code false} otherwise.
         */
        boolean cancel();

        /**
         * @return True if the task must be run under progress (a progress indicator is created automatically). Otherwise, the task is
         * responsible for visualizing the progress by itself, it is just started on a background thread.
         */
        default boolean isRunUnderProgress() {
            return false;
        }
    }

    // region ExternalFormatProcessor (Consulo)

    @Override
    @RequiredReadAction
    public boolean activeForFile(PsiFile source) {
        return canFormat(source) || (getFeatures().contains(Feature.FORMAT_FRAGMENTS) && canFormat(source, Feature.FORMAT_FRAGMENTS));
    }

    @Override
    @RequiredReadAction
    public @Nullable TextRange format(PsiFile source, TextRange range, boolean canChangeWhiteSpacesOnly) {
        Document document = PsiDocumentManager.getInstance(source.getProject()).getDocument(source);
        if (document == null) {
            return null;
        }
        boolean isFullRange = range.getStartOffset() == 0 && range.getEndOffset() >= document.getTextLength();
        boolean canFormat = isFullRange ? canFormat(source) : canFormat(source, Feature.FORMAT_FRAGMENTS);
        if (!canFormat) {
            return null;
        }
        TextRange formattingRange = isFullRange ? TextRange.create(0, document.getTextLength()) : range;
        FormattingContext formattingContext =
            FormattingContext.create(source, formattingRange, CodeStyle.getSettings(source), FormattingMode.REFORMAT);
        formatDocument(document, List.of(formattingRange), formattingContext, canChangeWhiteSpacesOnly, false);
        return range;
    }

    @Override
    public @Nullable String indent(PsiFile source, int lineStartOffset) {
        return null;
    }

    @Override
    public String getId() {
        return getClass().getName();
    }

    // endregion

    public final void formatDocument(
        Document document,
        List<TextRange> formattingRanges,
        FormattingContext formattingContext,
        boolean canChangeWhiteSpaceOnly,
        boolean quickFormat
    ) {
        synchronized (this) {
            FormattingRequestImpl currRequest = myPendingRequests.get(document);
            boolean isSync = isSyncFormat(document);
            if (currRequest != null) {
                if (!currRequest.cancel()) {
                    LOG.warn("Pending request can't be cancelled");
                    return;
                }
            }
            prepareForFormatting(document, formattingContext);
            FormattingRequestImpl formattingRequest =
                new FormattingRequestImpl(formattingContext, document, formattingRanges, canChangeWhiteSpaceOnly, quickFormat);
            FormattingTask formattingTask = createFormattingTask(formattingRequest);
            if (formattingTask != null) {
                formattingRequest.myTask = formattingTask;
                myPendingRequests.put(document, formattingRequest);
                if (isSync) {
                    runSyncFormat(formattingRequest);
                }
                else {
                    launchAsyncFormat(formattingRequest);
                }
            }
        }
    }

    private static boolean isSyncFormat(Document document) {
        Application application = Application.get();
        return Boolean.TRUE.equals(document.getUserData(FORMAT_DOCUMENT_SYNCHRONOUSLY))
            || application.isHeadlessEnvironment()
            || application.isUnitTestMode();
    }

    private void runSyncFormat(FormattingRequestImpl request) {
        try {
            FormattingTask task = request.requireTask();
            request.myTaskStarted = true;
            task.run();
            if (request.myResult.isDone() && !request.myResult.isCompletedExceptionally()) {
                String formattedText = request.myResult.getNow(null);
                if (formattedText != null) {
                    request.updateDocument(formattedText);
                }
            }
        }
        finally {
            myPendingRequests.remove(request.myDocument, request);
        }
    }

    private void launchAsyncFormat(FormattingRequestImpl request) {
        FormattingTask task = request.requireTask();
        Project project = request.getContext().getProject();
        Runnable runTask = () -> {
            request.myTaskStarted = true;
            try {
                task.run();
            }
            catch (ProcessCanceledException e) {
                request.myResult.completeExceptionally(new CancellationException());
            }
            catch (Throwable t) {
                request.myResult.completeExceptionally(t);
                LOG.error(t);
            }
        };

        long timeoutMs = getTimeout().toMillis();
        request.myResult.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).whenComplete((formattedText, t) -> {
            myPendingRequests.remove(request.myDocument, request);
            if (t instanceof TimeoutException) {
                task.cancel();
                notifyError(project, getName(), LspLocalize.asyncFormattingServiceTimeout(getName(), getTimeout().toSeconds()));
                return;
            }
            if (t != null || formattedText == null) {
                return;
            }
            Application.get().invokeLater(() -> CommandProcessor.getInstance().runUndoTransparentAction(
                () -> WriteAction.run(() -> request.updateDocument(formattedText))
            ));
        });

        if (task.isRunUnderProgress()) {
            ProgressManager.getInstance().run(new Task.Backgroundable(project, LspLocalize.asyncFormattingServiceRunning(getName()), true) {
                @Override
                public void run(ProgressIndicator indicator) {
                    runTask.run();
                }
            });
        }
        else {
            AppExecutorUtil.getAppExecutorService().execute(runTask);
        }
    }

    private void notifyError(Project project, LocalizeValue title, LocalizeValue message) {
        NotificationService.getInstance().newError(getNotificationGroup())
            .title(title)
            .content(message)
            .create()
            .notify(project);
    }

    private final class FormattingRequestImpl implements AsyncFormattingRequest {
        private final FormattingContext myContext;
        private final Document myDocument;
        private final List<TextRange> myRanges;
        private final boolean myCanChangeWhitespaceOnly;
        private final boolean myQuickFormat;
        private final long myInitialModificationStamp;

        private volatile @Nullable FormattingTask myTask;
        private volatile boolean myTaskStarted;
        private final CompletableFuture<@Nullable String> myResult = new CompletableFuture<>();

        private FormattingRequestImpl(
            FormattingContext context,
            Document document,
            List<TextRange> ranges,
            boolean canChangeWhitespaceOnly,
            boolean quickFormat
        ) {
            myContext = context;
            myDocument = document;
            myRanges = ranges;
            myCanChangeWhitespaceOnly = canChangeWhitespaceOnly;
            myQuickFormat = quickFormat;
            myInitialModificationStamp = document.getModificationStamp();
        }

        private FormattingTask requireTask() {
            FormattingTask task = myTask;
            if (task == null) {
                throw new IllegalStateException("No formatting task");
            }
            return task;
        }

        private boolean cancel() {
            if (!myTaskStarted) {
                return false;
            }
            // for our purpose, myResult.cancel is equivalent, but we need the CAS semantics
            if (myResult.completeExceptionally(new CancellationException())) {
                return requireTask().cancel();
            }
            return false;
        }

        private void updateDocument(String newText) {
            if (!needToUpdate()) {
                return;
            }
            if (myDocument.getModificationStamp() > myInitialModificationStamp) {
                // there is no DocumentMerger extension point, the result for a changed document is dropped
                return;
            }
            myDocument.setText(newText);
        }

        @Override
        public void onTextReady(@Nullable String updatedText) {
            myResult.complete(updatedText);
        }

        @Override
        public void onError(LocalizeValue title, LocalizeValue message) {
            if (myResult.complete(null)) {
                notifyError(myContext.getProject(), title, message);
            }
        }

        @Override
        public String getDocumentText() {
            return myDocument.getText();
        }

        @Override
        public List<TextRange> getFormattingRanges() {
            return myRanges;
        }

        @Override
        public boolean canChangeWhitespaceOnly() {
            return myCanChangeWhitespaceOnly;
        }

        @Override
        public FormattingContext getContext() {
            return myContext;
        }

        @Override
        public boolean isQuickFormat() {
            return myQuickFormat;
        }
    }
}

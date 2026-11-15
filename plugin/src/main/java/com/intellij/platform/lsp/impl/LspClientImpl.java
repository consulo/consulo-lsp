package com.intellij.platform.lsp.impl;

import com.intellij.platform.lsp.api.Lsp4jServer;
import com.intellij.platform.lsp.api.LspClientDescriptor;
import com.intellij.platform.lsp.api.LspClientManagerListener;
import com.intellij.platform.lsp.api.LspCommunicationChannel;
import com.intellij.platform.lsp.api.LspIntegrationProvider;
import com.intellij.platform.lsp.api.LspServer;
import com.intellij.platform.lsp.api.LspServerListener;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.platform.lsp.api.customization.LspInheritanceMarkersSupport;
import com.intellij.platform.lsp.impl.connector.Lsp4jServerConnector;
import com.intellij.platform.lsp.impl.connector.Lsp4jServerConnectorSocket;
import com.intellij.platform.lsp.impl.connector.Lsp4jServerConnectorStdio;
import com.intellij.platform.lsp.impl.connector.LspInitializationException;
import com.intellij.platform.lsp.impl.documentSync.LspDocumentSyncManager;
import com.intellij.platform.lsp.impl.features.LspFeaturesRefreshing;
import com.intellij.platform.lsp.impl.features.highlighting.DiagnosticAndQuickFixes;
import com.intellij.platform.lsp.impl.features.highlighting.LspHighlightingApplier;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspHighlightingCacheRegistry;
import com.intellij.platform.lsp.impl.features.inlayCommon.LspInlayApplier;
import com.intellij.platform.lsp.impl.features.navigation.LspDynamicFiles;
import com.intellij.platform.lsp.impl.fileEvents.LspWatchedFiles;
import consulo.annotation.access.RequiredReadAction;
import consulo.application.Application;
import consulo.application.ReadAction;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.document.event.DocumentEvent;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.lang.ExceptionUtil;
import consulo.util.lang.StringUtil;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Upstream: {@code class LspClientImpl : LspClientRenameCompat}, where {@code LspClientRenameCompat} is a typealias of the deprecated {@link LspServer}.
 * The deprecated typealias {@code LspServerImpl = LspClientImpl} has no Java counterpart and is not ported.
 */
@SuppressWarnings("deprecation")
public class LspClientImpl implements LspServer {
    private static final Logger LOG = Logger.getInstance(LspClientImpl.class);

    public static final int NOT_CANCELLABLE_REQUEST_TIMEOUT_MS = 300;
    private static final int MAX_ERROR_OUTPUT_SIZE = 1024 * 1024; // FileUtilRt.MEGABYTE

    private final Class<? extends LspIntegrationProvider> myProviderClass;
    private final LspClientDescriptor myDescriptor;
    private final LspClientManagerListener myEventBroadcaster;
    private final Project myProject;

    private volatile LspServerState myState = LspServerState.Initializing;
    private final Object myStateLock = new Object();

    private volatile @Nullable InitializeResult myInitializeResult;

    private final LspDocumentMapping myDocumentMapping;
    private final LspRequestExecutor myRequestExecutor;
    private final LspGlobMatcher myGlobMatcher;
    private final LspDynamicCapabilities myDynamicCapabilities;
    private final LspServerNotificationsHandlerImpl myServerNotificationsHandler;

    private final LspDocumentSyncManager myDocumentSyncManager;
    private final LspWatchedFiles myWatchedFiles;
    private final LspDynamicFiles myDynamicFiles;
    private final Set<String> myUnsupportedFilePaths = Collections.synchronizedSet(new HashSet<>());
    private final LspHighlightingCacheRegistry myHighlightingCacheRegistry;

    private volatile @Nullable Lsp4jServerConnector myLsp4jServerConnector;
    private final Object myConnectorLock = new Object();

    private final StringBuilder myErrorOutputBuffer = new StringBuilder();

    /**
     * Holds the effective {@link ClientCapabilities} sent to the LSP server in {@link org.eclipse.lsp4j.InitializeParams}.
     * <p>
     * Unlike {@link LspClientDescriptor#getClientCapabilities} or {@link LspClientDescriptor#createInitializeParams},
     * this property guarantees the same instance across multiple reads.
     * <p>
     * The value is {@code null} before the server initializes.
     */
    private volatile @Nullable ClientCapabilities myClientCapabilities;

    public LspClientImpl(Class<? extends LspIntegrationProvider> providerClass,
                         LspClientDescriptor descriptor,
                         LspClientManagerListener eventBroadcaster) {
        myProviderClass = providerClass;
        myDescriptor = descriptor;
        myEventBroadcaster = eventBroadcaster;
        myProject = descriptor.getProject();

        myDocumentMapping = new LspDocumentMapping(this);
        myRequestExecutor = new LspRequestExecutor(this, myDocumentMapping);
        myGlobMatcher = new LspGlobMatcher();
        myDynamicCapabilities = new LspDynamicCapabilities();
        myServerNotificationsHandler = new LspServerNotificationsHandlerImpl(this);

        myDocumentSyncManager = new LspDocumentSyncManager(this);
        myWatchedFiles = new LspWatchedFiles(this);
        myDynamicFiles = new LspDynamicFiles(this);
        myHighlightingCacheRegistry = new LspHighlightingCacheRegistry(this);
    }

    @Override
    public Class<? extends LspIntegrationProvider> getProviderClass() {
        return myProviderClass;
    }

    @Override
    public LspClientDescriptor getDescriptor() {
        return myDescriptor;
    }

    @Override
    public Project getProject() {
        return myProject;
    }

    @Override
    public LspServerState getState() {
        return myState;
    }

    private void setState(LspServerState value) {
        LspServerState field = myState;
        if (value == LspServerState.Initializing ||
            (field != LspServerState.Initializing && value == LspServerState.Running) ||
            field == LspServerState.ShutdownNormally ||
            field == LspServerState.ShutdownUnexpectedly) {
            LOG.error("Incorrect state change: " + field + " -> " + value);
            return;
        }
        myState = value;
        myEventBroadcaster.serverStateChanged(this);
    }

    @Override
    public @Nullable InitializeResult getInitializeResult() {
        return myInitializeResult;
    }

    public LspDocumentMapping getDocumentMapping() {
        return myDocumentMapping;
    }

    public LspRequestExecutor getRequestExecutor() {
        return myRequestExecutor;
    }

    public LspGlobMatcher getGlobMatcher() {
        return myGlobMatcher;
    }

    public LspDynamicCapabilities getDynamicCapabilities() {
        return myDynamicCapabilities;
    }

    public LspServerNotificationsHandlerImpl getServerNotificationsHandler() {
        return myServerNotificationsHandler;
    }

    public LspDocumentSyncManager getDocumentSyncManager() {
        return myDocumentSyncManager;
    }

    public LspWatchedFiles getWatchedFiles() {
        return myWatchedFiles;
    }

    public LspDynamicFiles getDynamicFiles() {
        return myDynamicFiles;
    }

    public LspHighlightingCacheRegistry getHighlightingCacheRegistry() {
        return myHighlightingCacheRegistry;
    }

    public @Nullable String getErrorOutput() {
        synchronized (myErrorOutputBuffer) {
            return StringUtil.nullize(myErrorOutputBuffer.toString());
        }
    }

    public Lsp4jServer getLsp4jServer() {
        Lsp4jServerConnector connector = myLsp4jServerConnector;
        if (connector == null) {
            throw new IllegalStateException("lsp4jServerConnector has not been initialized");
        }
        return connector.getLsp4jServer();
    }

    public @Nullable ClientCapabilities getClientCapabilities() {
        return myClientCapabilities;
    }

    public @Nullable ServerCapabilities getServerCapabilities() {
        InitializeResult initializeResult = myInitializeResult;
        return myState == LspServerState.Running && initializeResult != null ? initializeResult.getCapabilities() : null;
    }

    public boolean isFileOpened(VirtualFile file) {
        return myDocumentSyncManager.isFileOpened(file);
    }

    public void forEachOpenedFile(Consumer<VirtualFile> action) {
        myDocumentSyncManager.forEachOpenedFile(action);
    }

    public void notifyFileOpened(VirtualFile file) {
        myEventBroadcaster.fileOpened(this, file);
    }

    @Override
    public void sendNotification(Consumer<Lsp4jServer> lsp4jSender) {
        myRequestExecutor.sendNotification(lsp4jSender);
    }

    @Override
    public <Lsp4jResponse> CompletableFuture<@Nullable Lsp4jResponse> sendRequest(Function<Lsp4jServer, CompletableFuture<Lsp4jResponse>> lsp4jSender) {
        return myRequestExecutor.sendRequest(lsp4jSender);
    }

    /**
     * Must be called on a background thread.
     */
    @Override
    public <Lsp4jResponse> @Nullable Lsp4jResponse sendRequestSync(Function<Lsp4jServer, CompletableFuture<Lsp4jResponse>> lsp4jSender) {
        return myRequestExecutor.sendRequestSync(lsp4jSender);
    }

    /**
     * Must be called on a background thread.
     */
    @Override
    public <Lsp4jResponse> @Nullable Lsp4jResponse sendRequestSync(int timeoutMs,
                                                                   Function<Lsp4jServer, CompletableFuture<Lsp4jResponse>> lsp4jSender) {
        return myRequestExecutor.sendRequestSync(timeoutMs, lsp4jSender);
    }

    @Override
    public TextDocumentIdentifier getDocumentIdentifier(VirtualFile file) {
        return new TextDocumentIdentifier(myDescriptor.getFileUri(file));
    }

    @Override
    public int getDocumentVersion(Document document) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null) {
            return -1;
        }
        return myDocumentSyncManager.currentDocumentVersion(file);
    }

    @Override
    public int nextDocumentVersion(Document document) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null) {
            return -1;
        }
        return myDocumentSyncManager.nextDocumentVersion(file);
    }

    /**
     * Must be called on a background thread.
     */
    @RequiredReadAction
    public boolean isSupportedFile(VirtualFile file) {
        if (myUnsupportedFilePaths.contains(file.getPath())) {
            return false;
        }

        boolean supported = myDescriptor.isSupportedFile(file);
        if (!supported) {
            myUnsupportedFilePaths.add(file.getPath());
        }
        return supported;
    }

    public void diagnosticsReceived(PublishDiagnosticsParams params) {
        myHighlightingCacheRegistry.getPublishDiagnosticsCache().diagnosticsReceived(params);
    }

    public void fileEdited(VirtualFile file, DocumentEvent e) {
        myHighlightingCacheRegistry.fileEdited(file, e);
        myEventBroadcaster.fileEdited(this, file);
        if (isFileOpened(file)) {
            // Re-apply the cached highlightings with the edit-adjusted ranges. Without this, highlightings
            // applied before the edit keep their pre-edit offsets until the next daemon pass.
            LspHighlightingApplier.getInstance(myProject).scheduleHighlightingRefreshDebounced(file);
        }
    }

    public void fileClosed(VirtualFile file) {
        myHighlightingCacheRegistry.fileClosed(file);
    }

    @Override
    public void invalidateServerResults() {
        myRequestExecutor.clearCaches();
        forEachOpenedFile(file -> {
            myHighlightingCacheRegistry.invalidatePulledResults(file);
            LspHighlightingApplier.getInstance(myProject).scheduleHighlightingRefresh(file);
            LspInlayApplier.getInstance(myProject).scheduleRefresh(file);
        });
        if (!myProject.isDisposed()) {
            LspFeaturesRefreshing.refreshCodeLenses(myProject);
        }
    }

    public void refreshSemanticTokens() {
        myHighlightingCacheRegistry.getSemanticTokensCache().clearCache();
        forEachOpenedFile(file -> LspHighlightingApplier.getInstance(myProject).scheduleHighlightingRefresh(file));
    }

    /**
     * Handles a server-forced {@code workspace/inlayHint/refresh}: re-requests inlay hints for every opened file even without
     * a document edit, then re-applies out-of-band. Invalidating the cache keeps the current hints on screen (no
     * flicker); {@link LspInlayApplier#scheduleRefresh} kicks the re-request and diffs in the fresh hints once they land.
     */
    public void refreshInlayHints() {
        forEachOpenedFile(file -> {
            myHighlightingCacheRegistry.getInlayHintsCache().invalidate(file);
            LspInlayApplier.getInstance(myProject).scheduleRefresh(file);
        });
    }

    /**
     * Handles a server-forced {@code workspace/diagnostic/refresh}, and a server re-registering {@code textDocument/diagnostic}:
     * both mean the diagnostics the IDE pulled no longer reflect what the server would answer now. Invalidating the
     * cache keeps the current diagnostics on screen (no flicker);
     * {@link LspHighlightingApplier#scheduleHighlightingRefresh} kicks the re-pull and applies the fresh results once they land.
     */
    public void refreshDiagnostics() {
        forEachOpenedFile(file -> {
            myHighlightingCacheRegistry.getPullDiagnosticsCache().forceFullRepull(file);
            LspHighlightingApplier.getInstance(myProject).scheduleHighlightingRefresh(file);
        });
    }

    /**
     * Must be called on a background thread.
     */
    public List<DiagnosticAndQuickFixes> getDiagnosticsAndQuickFixes(VirtualFile file) {
        return myHighlightingCacheRegistry.getDiagnosticsAndQuickFixes(file);
    }

    public void notifyDocumentLinksReceived(VirtualFile file) {
        myEventBroadcaster.documentLinksReceived(this, file);
    }

    public void notifyDiagnosticsReceived(VirtualFile file) {
        myEventBroadcaster.diagnosticsReceived(this, file);
    }

    public void start() {
        // Upstream throws IllegalStateException("Project is not trusted") here for safety.
        // Consulo has no TrustedProjects, every project is treated as trusted.

        if (myState != LspServerState.Initializing) {
            logError("start() cannot be called for a server twice");
            return;
        }

        logInfo("Starting LSP server");
        long startTime = System.nanoTime();

        Application.get().executeOnPooledThread(() -> {
            try {
                synchronized (myConnectorLock) {
                    Lsp4jServerConnector connector = createLsp4jServerConnector();
                    myLsp4jServerConnector = connector;
                    connector.connect((initializeParams, initializeResult) -> {
                        myInitializeResult = initializeResult;
                        myClientCapabilities = initializeParams.getCapabilities();

                        // While waiting for the server initialization, the `state` might have already become `ShutdownNormally`
                        // or `ShutdownUnexpectedly`, which means that the server is going to shut down shortly, so it must not get the Running state
                        synchronized (myStateLock) {
                            if (myState == LspServerState.Initializing) {
                                setState(LspServerState.Running);
                            }
                        }

                        String message = "LSP server initialized in " +
                            String.format(Locale.ROOT, "%.3fs", (System.nanoTime() - startTime) / 1_000_000_000.0);
                        ServerInfo serverInfo = initializeResult.getServerInfo();
                        logInfo(serverInfo != null ? message + ", name = " + serverInfo.getName() + ", version = " + serverInfo.getVersion() : message);
                        LspServerListener listener = myDescriptor.getLspServerListener();
                        if (listener != null) {
                            listener.serverInitialized(initializeResult);
                        }
                    });
                }
                myDocumentSyncManager.openForOpenedOrUnsavedFiles();
                LspFeaturesRefreshing.refreshBreadcrumbs();
                forEachOpenedFile(it -> LspInlayApplier.getInstance(myProject).scheduleRefresh(it));
                LspFeaturesRefreshing.refreshCodeLenses(myProject);
            }
            catch (Exception e) {
                // stack trace of the LspInitializationException is always the same, so not interesting; let's log its cause
                Throwable exToLog = e instanceof LspInitializationException && e.getCause() != null ? e.getCause() : e;
                // upstream checks `e is AlreadyDisposedException`; Consulo has no such exception, so check the project itself
                if (myProject.isDisposed()) {
                    // The project is disposed
                    ensureServerStopped(false, () -> {
                    });
                    return;
                }
                logWarn("Failed to start LSP server", exToLog);

                LspClientManagerImpl manager = ReadAction.compute(
                    () -> !myProject.isDisposed() ? LspClientManagerImpl.getInstanceImpl(myProject) : null
                );
                String text = (e instanceof LspInitializationException ? e + "\nCaused by:\n" : "") + ExceptionUtil.getThrowableText(exToLog);
                if (manager != null) {
                    manager.handleMaybeUnexpectedServerStop(this, text);
                }
            }
        });
    }

    private Lsp4jServerConnector createLsp4jServerConnector() throws Exception {
        LspCommunicationChannel channel = myDescriptor.getLspCommunicationChannel();
        if (channel instanceof LspCommunicationChannel.Socket) {
            return new Lsp4jServerConnectorSocket(this);
        }
        return new Lsp4jServerConnectorStdio(this);
    }

    public void ensureServerStopped(boolean explicitStop, Runnable updateLspServerManagerState) {
        synchronized (myStateLock) {
            updateLspServerManagerState.run();

            if (myState == LspServerState.ShutdownNormally || myState == LspServerState.ShutdownUnexpectedly) {
                return; // already shut down
            }

            logInfo("Stopping LSP server " + (explicitStop ? "normally" : "unexpectedly"));
            setState(explicitStop ? LspServerState.ShutdownNormally : LspServerState.ShutdownUnexpectedly);

            if (!myProject.isDisposed()) {
                boolean inheritanceMarkersEnabled =
                    myDescriptor.getLspCustomization().getInheritanceMarkersCustomizer() instanceof LspInheritanceMarkersSupport;
                forEachOpenedFile(file -> {
                    LspHighlightingApplier.getInstance(myProject).scheduleHighlightingRefresh(file);
                    LspInlayApplier.getInstance(myProject).scheduleRefresh(file);
                    if (inheritanceMarkersEnabled) {
                        LspFeaturesRefreshing.refreshLineMarkers(myProject, file);
                    }
                });
            }
            myDocumentSyncManager.shutdown();
            myRequestExecutor.shutdownNow();
            myServerNotificationsHandler.cancelAllProgress();
            myServerNotificationsHandler.releasePendingMessageRequests();

            myHighlightingCacheRegistry.clearCache();

            if (!myProject.isDisposed()) {
                LspFeaturesRefreshing.refreshCodeLenses(myProject);
            }
        }

        // A graceful `shutdown`/`exit` handshake only makes sense for an explicit stop of a still-responsive server.
        // On an unexpected stop the server-to-IDE channel is already dead, so skip the handshake and just disconnect.
        shutdownAndExit(explicitStop);
    }

    private void shutdownAndExit(boolean graceful) {
        Runnable shutdownAndExit = () -> {
            synchronized (myConnectorLock) {
                Lsp4jServerConnector connector = myLsp4jServerConnector;
                if (connector != null) {
                    connector.shutdownExitDisconnect(graceful);
                }
            }
        };

        Application application = Application.get();
        if (application.isDispatchThread() || application.isReadAccessAllowed()) {
            application.executeOnPooledThread(shutdownAndExit);
        }
        else {
            shutdownAndExit.run();
        }
    }

    public void appendServerErrorOutput(String text) {
        synchronized (myErrorOutputBuffer) {
            if (!myErrorOutputBuffer.isEmpty()) {
                myErrorOutputBuffer.append("\n");
            }
            if (text.length() > MAX_ERROR_OUTPUT_SIZE) {
                myErrorOutputBuffer.replace(0, myErrorOutputBuffer.length(), text.substring(text.length() - MAX_ERROR_OUTPUT_SIZE));
            }
            else if (myErrorOutputBuffer.length() + text.length() > MAX_ERROR_OUTPUT_SIZE) {
                myErrorOutputBuffer.delete(0, myErrorOutputBuffer.length() + text.length() - MAX_ERROR_OUTPUT_SIZE);
                myErrorOutputBuffer.append(text);
            }
            else {
                myErrorOutputBuffer.append(text);
            }
        }
    }

    @Override
    public String toString() {
        return myDescriptor + "(" + myState + ";" + myDocumentSyncManager.getOpenedFileCount() + ")";
    }

    public void logDebug(String message) {
        LOG.debug(decorateLogMessage(message));
    }

    public void logInfo(String message) {
        LOG.info(decorateLogMessage(message));
    }

    public void logWarn(String message) {
        logWarn(message, null);
    }

    public void logWarn(String message, @Nullable Throwable t) {
        LOG.warn(decorateLogMessage(message), t);
    }

    public void logError(String message) {
        LOG.error(decorateLogMessage(message));
    }

    private String decorateLogMessage(String message) {
        return this + ": " + message;
    }
}

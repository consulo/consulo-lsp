package com.intellij.platform.lsp.impl;

import com.intellij.platform.lsp.api.Lsp4jServer;
import com.intellij.platform.lsp.api.Lsp4jServerWrapper;
import com.intellij.platform.lsp.api.LspBulkIntegrationProvider;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspClientDescriptor;
import com.intellij.platform.lsp.api.LspClientManagerListener;
import com.intellij.platform.lsp.api.LspIntegrationProvider;
import com.intellij.platform.lsp.api.LspServer;
import com.intellij.platform.lsp.api.LspServerDescriptor;
import com.intellij.platform.lsp.api.LspServerManager;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.platform.lsp.api.LspServerSupportProvider;
import com.intellij.platform.lsp.impl.documentSync.LspOpenedFilesService;
import com.intellij.platform.lsp.impl.features.navigation.LspDynamicFiles;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ServiceImpl;
import consulo.application.Application;
import consulo.application.ReadAction;
import consulo.application.WriteAction;
import consulo.application.progress.ProgressManager;
import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.fileEditor.FileEditorManager;
import consulo.language.editor.DaemonCodeAnalyzer;
import consulo.logging.Logger;
import consulo.module.content.ProjectFileIndex;
import consulo.module.content.layer.event.ModuleRootEvent;
import consulo.module.content.layer.event.ModuleRootListener;
import consulo.project.Project;
import consulo.project.ProjectManager;
import consulo.proxy.EventDispatcher;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Project service for managing LSP servers for the current project.
 * <p>
 * Registered against {@link com.intellij.platform.lsp.api.LspClientManager} (the {@code @ServiceAPI} of the API module);
 * the deprecated {@link LspServerManager} is implemented as well, so {@code LspServerManager.getInstance(project)} keeps working.
 * <p>
 * The service is {@link Disposable}: Consulo disposes project services on project close, which stops every running server.
 * <p>
 * Consulo has no TrustedProjects: every project is treated as trusted.
 * The deprecated typealias {@code LspServerManagerImpl = LspClientManagerImpl} has no Java counterpart and is not ported.
 */
@ServiceImpl
@Singleton
@SuppressWarnings("deprecation")
public class LspClientManagerImpl implements LspServerManager, Disposable {
    private static final Logger LOG = Logger.getInstance(LspClientManagerImpl.class);
    private static final int MAX_LSP_CLIENTS = 10;

    /**
     * The stable identity of a server: the same across sessions, so a restarted client can recognize its predecessor's files.
     * The provider class is part of the identity, because two providers can use the same descriptor class, name, and roots.
     */
    public static String getServerId(Class<? extends LspIntegrationProvider> providerClass, LspClientDescriptor descriptor) {
        StringBuilder roots = new StringBuilder();
        VirtualFile[] descriptorRoots = descriptor.getRoots();
        for (int i = 0; i < descriptorRoots.length; i++) {
            if (i > 0) {
                roots.append(":");
            }
            roots.append(descriptorRoots[i].getPath());
        }
        return providerClass.getName() + ":" + descriptor.getClass().getName() + ":" + descriptor.getPresentableName() + ":" + roots;
    }

    public static String getServerId(LspClientImpl lspClient) {
        return getServerId(lspClient.getProviderClass(), lspClient.getDescriptor());
    }

    private final Project myProject;

    private final List<LspClientImpl> myLspClients = new CopyOnWriteArrayList<>();

    /**
     * Makes a client start in {@link #ensureStarted} atomic with the stop-stamp write and the client snapshot in {@link #stopClients}.
     */
    private final Object myStartStopLock = new Object();
    private final AtomicLong myStopClock = new AtomicLong();

    /**
     * Keyed by the provider class name for {@link #stopClients}, and by the server id for {@link #stopRunningServer}.
     * A string key is required: a {@code Class} key would retain the classloader of an unloaded plugin.
     */
    private final ConcurrentHashMap<String, Long> myLastStopStamps = new ConcurrentHashMap<>();

    private final List<Lsp4jServerWrapper> myLsp4jServerWrappers = new CopyOnWriteArrayList<>();

    private final EventDispatcher<LspClientManagerListener> myEventDispatcher = EventDispatcher.create(LspClientManagerListener.class);

    private volatile boolean myDisposed;

    @Inject
    public LspClientManagerImpl(Project project) {
        myProject = project;
        assert !project.isDefault() : "LspClientManager doesn't make sense for the default project";
        addExtensionPointListener();
        addWorkspaceModelListener();
    }

    @Override
    public Collection<LspClient> getClients(Class<? extends LspIntegrationProvider> providerClass) {
        return new ArrayList<>(getClientsImpl(providerClass));
    }

    /**
     * Consulo: upstream overrides {@code getClients} with the covariant {@code Collection<LspClientImpl>} return type,
     * which Java generics do not allow. Internal callers that need {@link LspClientImpl} use this function instead.
     */
    public Collection<LspClientImpl> getClientsImpl(Class<? extends LspIntegrationProvider> providerClass) {
        List<LspClientImpl> result = new ArrayList<>();
        for (LspClientImpl client : myLspClients) {
            if (client.getProviderClass() == providerClass) {
                result.add(client);
            }
        }
        return result;
    }

    public List<LspClientImpl> getAllClients() {
        return new ArrayList<>(myLspClients);
    }

    @Override
    @Deprecated
    public Collection<LspServer> getServersForProvider(Class<? extends LspServerSupportProvider> providerClass) {
        return new ArrayList<>(getClientsImpl(providerClass));
    }

    public Collection<LspClientImpl> getClientsWithThisFileOpen(VirtualFile file) {
        List<LspClientImpl> result = new ArrayList<>();
        for (LspClientImpl client : myLspClients) {
            if (client.isFileOpened(file)) {
                result.add(client);
            }
        }
        return result;
    }

    /**
     * Clients that should handle requests like hover or go-to-definition for the given file.
     * A dynamic file goes to the client that produced it (see {@link LspDynamicFiles}): an in-memory content file is requested
     * with the URI it was produced from.
     * A content file that lost its producing client, for example after a server restart, is adopted:
     * the replacement of the producing client, found by the same server identity, registers the file
     * and reports it open again, so existing editor tabs keep working.
     * Must be called on a background thread.
     */
    @RequiredReadAction
    public Collection<LspClientImpl> getClientsForFileRequests(VirtualFile file) {
        Collection<LspClientImpl> clientsWithFileOpen = getClientsWithThisFileOpen(file);
        if (!clientsWithFileOpen.isEmpty()) {
            return clientsWithFileOpen;
        }
        List<LspClientImpl> producingClients = new ArrayList<>();
        for (LspClientImpl client : getRunningClients()) {
            if (client.getDynamicFiles().contains(file)) {
                producingClients.add(client);
            }
        }
        if (!producingClients.isEmpty()) {
            return producingClients;
        }
        String contentUri = LspDynamicFiles.getDynamicFileUri(file);
        if (contentUri == null) {
            return List.of();
        }
        List<LspClientImpl> adoptingClients = new ArrayList<>();
        for (LspClientImpl client : getRunningClients()) {
            if (client.getDynamicFiles().adopt(file, contentUri)) {
                adoptingClients.add(client);
            }
        }
        return adoptingClients;
    }

    public Collection<LspClientImpl> getRunningClients() {
        List<LspClientImpl> result = new ArrayList<>();
        for (LspClientImpl client : myLspClients) {
            if (client.getState() == LspServerState.Running) {
                result.add(client);
            }
        }
        return result;
    }

    public boolean hasInitializingOrRunningClients() {
        for (LspClientImpl client : myLspClients) {
            if (client.getState() == LspServerState.Initializing || client.getState() == LspServerState.Running) {
                return true;
            }
        }
        return false;
    }

    public @Nullable LspClientImpl findRunningClient(Predicate<LspClientImpl> condition) {
        for (LspClientImpl client : myLspClients) {
            if (client.getState() == LspServerState.Running && condition.test(client)) {
                return client;
            }
        }
        return null;
    }

    @Override
    public void startClientsIfNeeded(Class<? extends LspIntegrationProvider> providerClass) {
        startIfNeeded(providerClass);
    }

    @Override
    @Deprecated
    public void startServersIfNeeded(Class<? extends LspServerSupportProvider> providerClass) {
        startIfNeeded(providerClass);
    }

    private void startIfNeeded(Class<? extends LspIntegrationProvider> providerClass) {
        // Consulo has no TrustedProjects: every project is treated as trusted

        LspIntegrationProvider provider = null;
        for (LspIntegrationProvider it : LspIntegrationProvider.getAllExtensions()) {
            if (it.getClass() == providerClass) {
                provider = it;
                break;
            }
        }
        if (provider == null) {
            LOG.error(providerClass.getName() + " is not loaded");
            return;
        }
        LspIntegrationProvider foundProvider = provider;

        long requestStamp = startRequestStamp();
        // upstream: cs.launch { val descriptorsToStart = readAction { ... }; descriptorsToStart.forEach { ensureStarted(...) } }
        ReadAction.nonBlocking(() -> {
                Collection<LspClientImpl> clients = getClientsImpl(providerClass);
                List<LspClientDescriptor> descriptorsToStart = new ArrayList<>();
                // a bulk provider may want one more client for a file within the roots of its other clients
                boolean bulk = foundProvider instanceof LspBulkIntegrationProvider;

                for (VirtualFile file : FileEditorManager.getInstance(myProject).getOpenFiles()) {
                    ProgressManager.checkCanceled();
                    if (!ProjectFileIndex.getInstance(myProject).isInContent(file)) {
                        continue;
                    }

                    if (!bulk && clients.stream().anyMatch(client -> isWithinRoots(client.getDescriptor(), file))) {
                        // the file is already within the roots of a running server
                        continue;
                    }

                    if (!bulk && descriptorsToStart.stream().anyMatch(descriptor -> isWithinRoots(descriptor, file))) {
                        // the file is already within the roots of a server that will start soon
                        continue;
                    }

                    LspStarterImpl starter = new LspStarterImpl(foundProvider);
                    callFileOpened(foundProvider, file, starter);
                    descriptorsToStart.addAll(starter.getDescriptors());
                }
                return descriptorsToStart;
            })
            .expireWith(this)
            .submit(AppExecutorUtil.getAppExecutorService())
            .onSuccess(descriptorsToStart -> {
                for (LspClientDescriptor descriptor : descriptorsToStart) {
                    ensureStarted(providerClass, descriptor, requestStamp);
                }
            });
    }

    private static boolean isWithinRoots(LspClientDescriptor descriptor, VirtualFile file) {
        for (VirtualFile root : descriptor.getRoots()) {
            if (VirtualFileUtil.isAncestor(root, file, true)) {
                return true;
            }
        }
        return false;
    }

    @RequiredReadAction
    private void callFileOpened(LspIntegrationProvider provider, VirtualFile file, LspStarterImpl starter) {
        provider.fileOpened(myProject, file, starter);
    }

    @Override
    public void ensureClientStarted(Class<? extends LspIntegrationProvider> providerClass, LspClientDescriptor descriptor) {
        ensureStarted(providerClass, descriptor);
    }

    @Override
    @Deprecated
    public void ensureServerStarted(Class<? extends LspServerSupportProvider> providerClass, LspServerDescriptor descriptor) {
        ensureStarted(providerClass, descriptor);
    }

    public @Nullable CompletableFuture<Void> ensureStarted(Class<? extends LspIntegrationProvider> providerClass, LspClientDescriptor descriptor) {
        return ensureStarted(providerClass, descriptor, null);
    }

    /**
     * {@code requestStamp} is the {@link #startRequestStamp} value captured at the event that requested this start.
     * When {@link #stopClients} for the provider, or {@link #stopRunningServer} for the same server, came after the event,
     * the request is stale and the start is skipped.
     * {@code null} means an explicit request that always starts. Returns the started task (upstream: the coroutine {@code Job}),
     * so a test can join it.
     */
    public @Nullable CompletableFuture<Void> ensureStarted(
        Class<? extends LspIntegrationProvider> providerClass,
        LspClientDescriptor descriptor,
        @Nullable Long requestStamp
    ) {
        // Consulo has no TrustedProjects: every project is treated as trusted
        if (myDisposed) {
            return null;
        }

        CompletableFuture<Void> job = new CompletableFuture<>();
        // TODO serviceView stage: LspServiceViewSupport.getInstance(project) - make sure its listener is registered before the first
        //  `clientAdded` event, so the console gets all lifecycle events

        // upstream: readAndEdtWriteAction { ... writeAction { ... } }
        Application.get().invokeLater(() -> {
            try {
                if (myDisposed || myProject.isDisposed()) {
                    return;
                }
                LspClientImpl client = WriteAction.compute(() -> {
                    String serverId = getServerId(providerClass, descriptor);
                    for (LspClientImpl existing : myLspClients) {
                        if (getServerId(existing).equals(serverId)) {
                            return null;
                        }
                    }

                    if (myLspClients.size() >= MAX_LSP_CLIENTS) {
                        LOG.error(myLspClients.size() + " LSP servers are already running and one more wants to start." +
                            "To save system resources, this request will be ignored: " + descriptor);
                        return null;
                    }

                    synchronized (myStartStopLock) {
                        if (requestStamp != null && lastStopStamp(providerClass, descriptor) > requestStamp) {
                            return null;
                        }
                        LspClientImpl newClient = new LspClientImpl(providerClass, descriptor, myEventDispatcher.getMulticaster());
                        newClient.start();
                        myLspClients.add(newClient);
                        return newClient;
                    }
                });
                if (client != null) {
                    // listeners run outside the lock
                    myEventDispatcher.getMulticaster().clientAdded(client);
                }
            }
            finally {
                job.complete(null);
            }
        }, () -> {
            if (myProject.isDisposed()) {
                job.complete(null);
                return true;
            }
            return false;
        });
        return job;
    }

    /**
     * The stamp for a start request. Capture it at the event that requests the start, before any scheduling.
     * {@link #ensureStarted} drops the request when {@link #stopClients} for the provider,
     * or {@link #stopRunningServer} for the server, came after the captured stamp.
     */
    public long startRequestStamp() {
        return myStopClock.get();
    }

    private long lastStopStamp(Class<? extends LspIntegrationProvider> providerClass, LspClientDescriptor descriptor) {
        return Math.max(myLastStopStamps.getOrDefault(providerClass.getName(), 0L),
            myLastStopStamps.getOrDefault(getServerId(providerClass, descriptor), 0L));
    }

    @Override
    public void stopClients(Class<? extends LspIntegrationProvider> providerClass) {
        Collection<LspClientImpl> clients;
        synchronized (myStartStopLock) {
            myLastStopStamps.put(providerClass.getName(), myStopClock.incrementAndGet());
            clients = getClientsImpl(providerClass);
        }
        clients.forEach(this::stopRunningServer);
    }

    @Override
    @Deprecated
    public void stopServers(Class<? extends LspServerSupportProvider> providerClass) {
        stopClients(providerClass);
    }

    /**
     * Called when the server works fine but needs to be stopped for some reason.
     * For example, an action like {@code Stop server} or {@code Restart server} is invoked, or the project is closed.
     * The stamp is keyed by the server id, so the stop does not drop a queued sibling start of the same provider.
     */
    public void stopRunningServer(LspClientImpl lspClient) {
        synchronized (myStartStopLock) {
            myLastStopStamps.put(getServerId(lspClient), myStopClock.incrementAndGet());
        }
        lspClient.ensureServerStopped(true, () -> handleServerStop(lspClient, true));
    }

    /**
     * Stops {@code lspClient} and starts a new client with the same descriptor.
     * The restart must not depend on the open-editors scan of {@link #startIfNeeded}: the scan skips files outside the local
     * file system, so it revives no server when only a content file tab (see {@link LspDynamicFiles}) remains open.
     */
    public void restartClient(LspClientImpl lspClient) {
        stopRunningServer(lspClient);
        ensureStarted(lspClient.getProviderClass(), lspClient.getDescriptor());
    }

    /**
     * Called when the IDE detects that the server stopped working, for example,
     * the server process has terminated or the socket connection has been lost.
     * <ol>
     * <li>This might be expected because the server has recently been stopped by calling {@link #stopRunningServer}.
     * In this case, this function doesn't do anything.</li>
     * <li>Otherwise, the server termination is treated as an unexpected one.</li>
     * </ol>
     */
    public void handleMaybeUnexpectedServerStop(LspClientImpl lspClient, String serverOutput) {
        lspClient.ensureServerStopped(false, () -> {
            if (lspClient.getState() != LspServerState.ShutdownNormally) {
                lspClient.appendServerErrorOutput(serverOutput);
            }
            handleServerStop(lspClient, false);
        });
    }

    /**
     * Called from {@link #stopRunningServer} and from {@link #handleMaybeUnexpectedServerStop}. Not expected to be called from anywhere else.
     *
     * @param explicitStop <ul>
     *                     <li>{@code true} is passed by {@link #stopRunningServer}, which is called when the server is working fine but needs to be stopped for some reason</li>
     *                     <li>{@code false} is passed by {@link #handleMaybeUnexpectedServerStop}.
     *                     It is called when the IDE detects that the server stopped working, which may be both expected and unexpected</li>
     *                     </ul>
     */
    private void handleServerStop(LspClientImpl lspClient, boolean explicitStop) {
        if ((lspClient.getState() == LspServerState.Initializing || lspClient.getState() == LspServerState.Running) &&
            !myLspClients.contains(lspClient)) {
            LOG.error("LspClientManager doesn't know the server that it is asked to stop: " + lspClient);
        }

        if (explicitStop) {
            // The serverState might be already ShutdownUnexpectedly at this point.
            // `explicitStop == true` for a server that is already ShutdownUnexpectedly means one of the following:
            // - project closed,
            // - plugin unloaded,
            // - plugin-specific technology disabled in Settings (stopServers(providerClass) called),
            // - manual server restart (RestartLspServerAction).
            // In any case, we need to remove it from the `lspClients` collection
            if (myLspClients.remove(lspClient)) {
                myEventDispatcher.getMulticaster().clientRemoved(lspClient);
            }
        }
        else {
            // ShutdownUnexpectedly servers stay in the `lspClients` collection so that they show up as 'Terminated' in the status bar widget.
            // By the way, maybe try to auto-restart the server a couple of times if it has shutdown unexpectedly?
        }

        if (lspClient.getState() == LspServerState.Running && !myProject.isDisposed()) {
            DaemonCodeAnalyzer.getInstance(myProject).restart("LspClientManagerImpl.stop");
        }
    }

    @Override
    public void stopAndRestartClientsIfNeeded(Class<? extends LspIntegrationProvider> providerClass) {
        stopClients(providerClass);
        startClientsIfNeeded(providerClass);
    }

    @Override
    @Deprecated
    public void stopAndRestartIfNeeded(Class<? extends LspServerSupportProvider> providerClass) {
        stopAndRestartClientsIfNeeded(providerClass);
    }

    @Override
    public void addLsp4jServerWrapper(Lsp4jServerWrapper wrapper, Disposable parentDisposable) {
        myLsp4jServerWrappers.add(wrapper);
        Disposer.register(parentDisposable, () -> myLsp4jServerWrappers.remove(wrapper));
    }

    public Lsp4jServer wrapLsp4jServer(LspClientImpl lspClient, Lsp4jServer lsp4jServer) {
        Lsp4jServer wrappedLsp4jServer = lsp4jServer;
        for (Lsp4jServerWrapper wrapper : myLsp4jServerWrappers) {
            wrappedLsp4jServer = wrapper.wrapLsp4jServer(lspClient, wrappedLsp4jServer);
        }
        return wrappedLsp4jServer;
    }

    @Override
    public void addListener(LspClientManagerListener listener, Disposable parentDisposable, boolean sendEventsForExistingClients) {
        myEventDispatcher.addListener(listener, parentDisposable);

        if (sendEventsForExistingClients) {
            // Listeners in LspTestUtilKt need to know about events that happened before a test managed to register a listener
            for (LspClientImpl lspClient : myLspClients) {
                if (lspClient.getState() == LspServerState.ShutdownUnexpectedly) {
                    myEventDispatcher.getMulticaster().serverStateChanged(lspClient);
                }
                lspClient.forEachOpenedFile(it -> myEventDispatcher.getMulticaster().fileOpened(lspClient, it));
            }
        }
    }

    private void addExtensionPointListener() {
        // Upstream listens to the `LspIntegrationProvider.EP_NAME` and `LspServerSupportProvider.EP_NAME` extension points:
        // `extensionAdded` -> startClientsIfNeeded(extension.javaClass), `extensionRemoved` -> stopClients(extension.javaClass).
        // Consulo plugins are not loaded or unloaded at runtime and ExtensionPointName has no listener API, so there is nothing to listen to.
    }

    private void addWorkspaceModelListener() {
        // Upstream collects the WorkspaceModel event log and calls onProjectRootsChanged() for ContentRootEntity changes.
        // Consulo has no WorkspaceModel: the module roots topic reports the content root changes.
        myProject.getMessageBus().connect(this).subscribe(ModuleRootListener.class, new ModuleRootListener() {
            @Override
            public void rootsChanged(ModuleRootEvent event) {
                onProjectRootsChanged();
            }
        });
    }

    /**
     * Upstream: {@code suspend fun onProjectRootsChanged()}. The returned future completes once the opened files are processed
     * and the closing of the files that are not of interest is scheduled.
     */
    public CompletableFuture<Void> onProjectRootsChanged() {
        // The current implementation handles the following use cases:
        // - some file is open in the editor, but it doesn't belong to the project (for example, it is in an excluded folder)
        // - later this file becomes a project file for some reason (for example, its parent folder is unexcluded)
        // In this case, it might be needed to send `didOpen` request for this file (if there's an already running LSP server that wants to handle it),
        // or it might be needed to start an LSP server that wants to handle this file.
        // And the reverse one: a file that a server has opened becomes excluded, so the server gets `didClose` for it.

        // TODO Some running servers might need to update the roots they serve (`didChangeWorkspaceFolders` notification)
        // TODO Some running servers might need to be stopped if they serve roots that don't belong to the project anymore

        CompletableFuture<Void> result = new CompletableFuture<>();
        if (myDisposed || myProject.isDisposed()) {
            result.complete(null);
            return result;
        }
        ReadAction.nonBlocking(() -> {
                List<VirtualFile> files = new ArrayList<>();
                for (Document document : FileDocumentManager.getInstance().getUnsavedDocuments()) {
                    VirtualFile file = FileDocumentManager.getInstance().getFile(document);
                    if (file != null) {
                        files.add(file);
                    }
                }
                for (VirtualFile file : FileEditorManager.getInstance(myProject).getOpenFiles()) {
                    if (file != null) {
                        files.add(file);
                    }
                }
                LspOpenedFilesService.getInstance(myProject).processOpenedFiles(files);
                return files;
            })
            .expireWith(this)
            .submit(AppExecutorUtil.getAppExecutorService())
            .onSuccess(files -> {
                LspOpenedFilesService.getInstance(myProject).scheduleClosingFilesThatAreNotOfInterest();
                result.complete(null);
            })
            .onError(result::completeExceptionally);
        return result;
    }

    @Override
    public void dispose() {
        myDisposed = true;
        myLspClients.forEach(this::stopRunningServer);
    }

    @SuppressWarnings("deprecation")
    public static class LspStarterImpl implements LspIntegrationProvider.LspClientStarter, LspServerSupportProvider.LspServerStarter {
        private final LspIntegrationProvider myProvider;

        /**
         * An {@link LspBulkIntegrationProvider} gets a client per passed descriptor.
         * Any other provider runs one client for the file, so its last passed descriptor wins.
         */
        private final List<LspClientDescriptor> myDescriptors = new ArrayList<>();

        public LspStarterImpl(LspIntegrationProvider provider) {
            myProvider = provider;
        }

        public List<LspClientDescriptor> getDescriptors() {
            return myDescriptors;
        }

        @Override
        public void ensureClientStarted(LspClientDescriptor descriptor) {
            if (!(myProvider instanceof LspBulkIntegrationProvider)) {
                myDescriptors.clear();
            }
            myDescriptors.add(descriptor);
        }

        @Override
        public void ensureServerStarted(LspServerDescriptor descriptor) {
            ensureClientStarted(descriptor);
        }
    }

    public static LspClientManagerImpl getInstanceImpl(Project project) {
        return (LspClientManagerImpl) LspServerManager.getInstance(project);
    }

    public static void forEachRunningClientInEachProject(Consumer<LspClientImpl> action) {
        forEachRunningClientInEachProject(null, action);
    }

    /**
     * Must be called under a read action when {@code file} is not {@code null} (it calls {@link LspClientImpl#isSupportedFile}).
     */
    public static void forEachRunningClientInEachProject(@Nullable VirtualFile file, Consumer<LspClientImpl> action) {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed() || project.isDefault()) {
                continue;
            }
            for (LspClientImpl client : getInstanceImpl(project).myLspClients) {
                if (client.getState() == LspServerState.Running &&
                    (file == null || client.isFileOpened(file) && client.isSupportedFile(file))) {
                    action.accept(client);
                }
            }
        }
    }

    public static boolean isAnyServerRunning() {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed() || project.isDefault()) {
                continue;
            }
            for (LspClientImpl client : getInstanceImpl(project).myLspClients) {
                if (client.getState() == LspServerState.Running) {
                    return true;
                }
            }
        }
        return false;
    }
}

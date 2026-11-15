package com.intellij.platform.lsp.impl.serviceView;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspClientManager;
import com.intellij.platform.lsp.api.LspClientManagerListener;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.execution.service.ServiceEventListener;
import consulo.execution.service.ServiceEventListener.ServiceEvent;
import consulo.logging.Logger;
import consulo.project.Project;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerInfo;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Keeps per-{@link LspClient} consoles and translates {@link LspClientManagerListener} events
 * into Services tool window ({@link ServiceEventListener}) events.
 * <p>
 * Console lifetime matches the client's lifetime in {@link LspClientManagerImpl}'s list: a console survives an unexpected shutdown
 * (the client stays visible as 'terminated') and is disposed when the client is explicitly stopped/restarted or the project closes.
 */
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
@Singleton
public class LspServiceViewSupport implements Disposable {
    private static final Logger LOG = Logger.getInstance(LspServiceViewSupport.class);

    public static LspServiceViewSupport getInstance(Project project) {
        return project.getInstance(LspServiceViewSupport.class);
    }

    /**
     * Never-throwing accessor for hot paths like the LSP protocol reader/writer threads: an exception thrown there
     * would break the LSP connection. Doesn't create the service, so it is also safe during project shutdown.
     */
    public static @Nullable LspServiceViewSupport getInstanceIfCreated(Project project) {
        return project.isDisposed() ? null : project.getInstanceIfCreated(LspServiceViewSupport.class);
    }

    private final Project myProject;
    private final Map<LspClient, LspClientConsole> myConsoles = new ConcurrentHashMap<>();

    @Inject
    public LspServiceViewSupport(Project project, LspClientManager lspClientManager) {
        myProject = project;

        lspClientManager.addListener(
            new LspClientManagerListener() {
                @Override
                public void clientAdded(LspClient lspClient) {
                    printSafely(
                        lspClient,
                        it -> it.printLifecycle(
                            LspLocalize.servicesLspConsoleServerStarting(lspClient.getDescriptor().getPresentableName()).get()
                        )
                    );
                    fireServiceEvent(ServiceEvent.createServiceAddedEvent(lspClient, LspServiceViewContributor.class, null));
                }

                @Override
                public void serverStateChanged(LspClient lspClient) {
                    printSafely(lspClient, console -> {
                        switch (lspClient.getState()) {
                            case Initializing -> {
                            }
                            case Running -> {
                                InitializeResult initializeResult = lspClient.getInitializeResult();
                                ServerInfo serverInfo = initializeResult != null ? initializeResult.getServerInfo() : null;
                                List<String> nameAndVersionParts = new ArrayList<>();
                                String name = serverInfo != null ? serverInfo.getName() : null;
                                nameAndVersionParts.add(name != null ? name : lspClient.getDescriptor().getPresentableName());
                                if (serverInfo != null && serverInfo.getVersion() != null) {
                                    nameAndVersionParts.add(serverInfo.getVersion());
                                }
                                String nameAndVersion = String.join(" ", nameAndVersionParts);
                                console.printLifecycle(LspLocalize.servicesLspConsoleServerInitialized(nameAndVersion).get());
                            }
                            case ShutdownNormally -> console.printLifecycle(LspLocalize.servicesLspConsoleServerStopped().get());
                            case ShutdownUnexpectedly -> console.printLifecycle(LspLocalize.servicesLspConsoleServerTerminated().get(), true);
                        }
                    });
                    fireServiceEvent(ServiceEvent.createEvent(
                        ServiceEventListener.EventType.SERVICE_CHANGED,
                        lspClient,
                        LspServiceViewContributor.class
                    ));
                }

                @Override
                public void clientRemoved(LspClient lspClient) {
                    LspClientConsole console = myConsoles.remove(lspClient);
                    if (console != null) {
                        Disposer.dispose(console);
                    }
                    fireServiceEvent(ServiceEvent.createEvent(
                        ServiceEventListener.EventType.SERVICE_REMOVED,
                        lspClient,
                        LspServiceViewContributor.class
                    ));
                }
            },
            this
        );

        // Consoles for the clients that started before this service was created (they missed the `clientAdded` event)
        for (LspClient lspClient : LspClientManagerImpl.getInstanceImpl(project).getAllClients()) {
            getOrCreateConsole(lspClient);
        }
    }

    /**
     * Returns {@code null} for a client that is no longer tracked by {@link LspClientManagerImpl}
     * (so that late messages don't recreate a console for a removed client) or when this service is already disposed.
     */
    public @Nullable LspClientConsole getOrCreateConsole(LspClient lspClient) {
        LspClientConsole console = myConsoles.get(lspClient);
        if (console != null) {
            return console;
        }

        if (!LspClientManagerImpl.getInstanceImpl(myProject).getAllClients().contains(lspClient)) {
            return null;
        }

        LspClientConsole newConsole = new LspClientConsole(myProject);
        LspClientConsole existing = myConsoles.putIfAbsent(lspClient, newConsole);
        if (existing != null) {
            Disposer.dispose(newConsole);
            return existing;
        }
        if (!Disposer.tryRegister(this, newConsole)) {
            myConsoles.remove(lspClient, newConsole);
            Disposer.dispose(newConsole);
            return null;
        }
        return newConsole;
    }

    private void printSafely(LspClient lspClient, Consumer<LspClientConsole> print) {
        try {
            LspClientConsole console = getOrCreateConsole(lspClient);
            if (console != null) {
                print.accept(console);
            }
        }
        catch (Exception e) {
            LOG.warn("Failed to print to the LSP console", e);
        }
    }

    private void fireServiceEvent(ServiceEvent event) {
        if (!myProject.isDisposed()) {
            myProject.getMessageBus().syncPublisher(ServiceEventListener.TOPIC).handle(event);
        }
    }

    @Override
    public void dispose() {
        myConsoles.clear();
    }
}

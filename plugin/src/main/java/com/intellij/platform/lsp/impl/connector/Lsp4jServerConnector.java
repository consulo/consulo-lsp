package com.intellij.platform.lsp.impl.connector;

import com.google.gson.JsonParseException;
import com.intellij.platform.lsp.api.Lsp4jClient;
import com.intellij.platform.lsp.api.Lsp4jServer;
import com.intellij.platform.lsp.api.LspClientDescriptor;
import com.intellij.platform.lsp.api.LspServerListener;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.logging.LanguageServiceLogger;
import com.intellij.platform.lsp.impl.logging.LanguageServiceLoggerService;
import com.intellij.platform.lsp.impl.serviceView.LspClientConsole;
import com.intellij.platform.lsp.impl.serviceView.LspServiceViewSupport;
import consulo.application.Application;
import consulo.application.ReadAction;
import consulo.application.util.registry.Registry;
import consulo.logging.Logger;
import consulo.util.concurrent.ConcurrencyUtil;
import consulo.util.lang.ExceptionUtil;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint;
import org.eclipse.lsp4j.jsonrpc.json.JsonRpcMethod;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.json.StreamMessageConsumer;
import org.eclipse.lsp4j.jsonrpc.json.StreamMessageProducer;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

public abstract class Lsp4jServerConnector {
    private static final Logger LOG = Logger.getInstance(Lsp4jServerConnector.class);

    private final LspClientImpl myLspClient;
    private final LspClientDescriptor myDescriptor;
    private final Lsp4jClient myLsp4jClient;
    private volatile @Nullable Lsp4jServer myLsp4jServer;

    private volatile @Nullable LanguageServiceLogger myLsCommunicationLogger;

    protected Lsp4jServerConnector(LspClientImpl lspClient) {
        myLspClient = lspClient;
        myDescriptor = lspClient.getDescriptor();
        myLsp4jClient = myDescriptor.createLsp4jClient(lspClient.getServerNotificationsHandler());
    }

    public Lsp4jServer getLsp4jServer() {
        Lsp4jServer server = myLsp4jServer;
        if (server == null) {
            throw new IllegalStateException("lsp4jServer has not been initialized");
        }
        return server;
    }

    public void setLsp4jServer(Lsp4jServer lsp4jServer) {
        myLsp4jServer = lsp4jServer;
    }

    protected abstract OutputStream getIdeToServerStream();

    protected abstract InputStream getServerToIdeStream();

    protected abstract void prepareConnect() throws Exception;

    protected abstract boolean isConnectionAlive();

    protected abstract void disconnect();

    /**
     * Called on the LSP listener thread right after it has stopped reading {@link #getServerToIdeStream()}, either
     * because the server closed the stream (clean EOF) or because the listener loop failed. Implementations
     * that re-buffer the server output must release {@link #getServerToIdeStream()} here so that the process output reader
     * can never stay blocked writing into a full buffer while nobody drains it. Otherwise that reader thread
     * stays blocked, the process handler never observes the process ending ({@code processTerminated} never fires),
     * so neither the handler and its reader threads nor the external server process (a separate executable)
     * are ever cleaned up — and killing that OS process by hand would not help, because the thread is stuck
     * writing into an in-process Java pipe, not into the process (IJPL-250254).
     */
    protected void releaseServerToIdeStream() {
    }

    /**
     * Must be called on a background thread without a read lock.
     */
    public void connect(BiConsumer<InitializeParams, InitializeResult> onSuccess) throws Exception {
        prepareConnect();

        MessageJsonHandler messageJsonHandler = createMessageJsonHandler();
        RemoteEndpoint remoteEndpoint = new RemoteEndpoint(
            new StreamMessageConsumer(getIdeToServerStream(), messageJsonHandler), ServiceEndpoints.toEndpoint(myLsp4jClient));
        messageJsonHandler.setMethodProvider(remoteEndpoint);
        // get manager before touching lsp4jServer to avoid leaking partial state in case the project is disposed
        LspClientManagerImpl manager = LspClientManagerImpl.getInstanceImpl(myLspClient.getProject());
        Lsp4jServer lsp4jServer = ServiceEndpoints.toServiceObject(remoteEndpoint, myDescriptor.getLsp4jServerClass());
        lsp4jServer = myDescriptor instanceof Lsp4jServerWrapperCreator wrapperCreator ? wrapperCreator.wrapLsp4jServer(lsp4jServer) : lsp4jServer;
        lsp4jServer = manager.wrapLsp4jServer(myLspClient, lsp4jServer);
        myLsp4jServer = lsp4jServer;

        Application.get().executeOnPooledThread(() -> {
            ConcurrencyUtil.runUnderThreadName("LSP Listener: " + myDescriptor, () -> {
                LOG.debug(myDescriptor + ": LSP server listener thread started");
                if (LanguageServiceLoggerService.isDebugLogEnabled()) {
                    myLsCommunicationLogger = LanguageServiceLoggerService.getInstance().connect(myDescriptor.getPresentableName(), true);
                }
                try {
                    try (StreamMessageProducer messageProducer = new StreamMessageProducer(getServerToIdeStream(), messageJsonHandler)) {
                        messageProducer.listen(remoteEndpoint);
                    }
                }
                catch (Throwable e) {
                    myLspClient.appendServerErrorOutput(ExceptionUtil.getThrowableText(e));
                    LOG.error(myDescriptor.toString(), e);
                }
                finally {
                    LOG.debug(myDescriptor + ": LSP server listener thread finished");
                    // This thread was the only consumer of serverToIdeStream. Now that it stopped reading, release
                    // the stream so that the process output reader cannot block forever writing into a full buffer
                    // while the server process is still alive (IJPL-250254).
                    try {
                        releaseServerToIdeStream();
                    }
                    catch (Throwable e) {
                        LOG.warn(myDescriptor + ": failed to release the serverToIdeStream stream", e);
                    }
                    LspClientManagerImpl stopManager = ReadAction.compute(
                        () -> !myLspClient.getProject().isDisposed() ? LspClientManagerImpl.getInstanceImpl(myLspClient.getProject()) : null
                    );
                    String text = myDescriptor.getLspCommunicationChannel().getClass().getSimpleName() + " connection closed";
                    // handleMaybeUnexpectedServerStop normally tires to run shutdown & exit, it's important NOT to do it in this finally block
                    if (stopManager != null) {
                        stopManager.handleMaybeUnexpectedServerStop(myLspClient, text);
                    }
                }
            });
        });

        initializeLsp4jServer(onSuccess);
    }

    private void initializeLsp4jServer(BiConsumer<InitializeParams, InitializeResult> onSuccess) throws InterruptedException {
        LOG.debug(myDescriptor + ": initializing LSP server");

        AtomicReference<@Nullable Throwable> error = new AtomicReference<>();
        CountDownLatch countDownLatch = new CountDownLatch(1);

        InitializeParams initializeParams = myDescriptor.createInitializeParams();

        Lsp4jServer lsp4jServer = getLsp4jServer();
        lsp4jServer.initialize(initializeParams)
            .whenComplete((InitializeResult result, @Nullable Throwable th) -> {
                if (result != null) {
                    lsp4jServer.initialized(new InitializedParams());
                    onSuccess.accept(initializeParams, result);
                }
                else {
                    error.set(th != null ? th : new RuntimeException("No InitializeResult"));
                }
                countDownLatch.countDown();
            });

        int timeoutInSeconds = Registry.intValue("lsp.server.connect.timeout", 100);
        boolean success = countDownLatch.await(timeoutInSeconds, TimeUnit.SECONDS);
        if (!success) {
            throw new RuntimeException("'initialized' response not received from the server in " + timeoutInSeconds + " seconds");
        }
        Throwable th = error.get();
        if (th != null) {
            throw new LspInitializationException("LSP server failed to initialize", th);
        }
    }

    /**
     * Must be called on a background thread without a read lock.
     */
    public void shutdownExitDisconnect(boolean graceful) {
        try {
            // On an unexpected stop the listener thread is no longer reading serverToIdeStream, so the shutdown response
            // can never arrive and `shutdown().get(...)` could only time out — skip it and just disconnect.
            // (Alternatively, we could send shutdown without waiting for a response.)
            if (graceful) {
                gracefulShutdownAndExit();
            }
        }
        finally {
            disconnectAndNotifyStopped();
        }
    }

    private void gracefulShutdownAndExit() {
        Lsp4jServer lsp4jServer = myLsp4jServer;
        try {
            if (lsp4jServer != null && isConnectionAlive()) {
                lsp4jServer.shutdown().get(10, TimeUnit.SECONDS);
            }
        }
        catch (Exception e) {
            LOG.warn(myDescriptor + ": `shutdown` request failed: " + e);
        }
        finally {
            if (lsp4jServer != null && isConnectionAlive()) {
                lsp4jServer.exit();
            }
        }
    }

    private void disconnectAndNotifyStopped() {
        try {
            LanguageServiceLogger logger = myLsCommunicationLogger;
            if (logger != null) {
                LanguageServiceLoggerService.getInstance().disconnect(logger);
            }
            myLsCommunicationLogger = null;
            disconnect();
        }
        finally {
            LspServerListener listener = myDescriptor.getLspServerListener();
            if (listener != null) {
                listener.serverStopped(myLspClient.getState() == LspServerState.ShutdownNormally);
            }
        }
    }

    private MessageJsonHandler createMessageJsonHandler() {
        Class<? extends Lsp4jServer> lsp4jServerClass = myDescriptor.getLsp4jServerClass();
        Map<String, JsonRpcMethod> supportedMethods = new LinkedHashMap<>(ServiceEndpoints.getSupportedMethods(lsp4jServerClass));
        supportedMethods.putAll(ServiceEndpoints.getSupportedMethods(myLsp4jClient.getClass()));
        return new MessageJsonHandler(supportedMethods, gsonBuilder -> gsonBuilder.disableHtmlEscaping()) {
            @Override
            public String serialize(Message message) {
                String serialized = super.serialize(message);
                LanguageServiceLogger logger = myLsCommunicationLogger;
                if (logger != null) {
                    logger.logOutbound(serialized);
                }
                printTrafficSafely(true, message, serialized);
                return serialized;
            }

            @Override
            public Message parseMessage(Reader input) throws JsonParseException {
                String content = readText(input);
                LanguageServiceLogger logger = myLsCommunicationLogger;
                if (logger != null) {
                    logger.logInbound(content);
                }
                Message message = super.parseMessage(new StringReader(content));
                if (message != null) {
                    printTrafficSafely(false, message, content);
                }
                return message;
            }
        };
    }

    private static String readText(Reader input) {
        try {
            StringWriter writer = new StringWriter();
            input.transferTo(writer);
            return writer.toString();
        }
        catch (IOException e) {
            throw new JsonParseException(e);
        }
    }

    /**
     * Must never throw: an exception thrown from {@link MessageJsonHandler#serialize}/{@link MessageJsonHandler#parseMessage}
     * would break the LSP connection.
     */
    private void printTrafficSafely(boolean outbound, Message message, String json) {
        try {
            LspServiceViewSupport serviceViewSupport = LspServiceViewSupport.getInstanceIfCreated(myLspClient.getProject());
            LspClientConsole console = serviceViewSupport != null ? serviceViewSupport.getOrCreateConsole(myLspClient) : null;
            if (console != null) {
                console.printTraffic(outbound, message, json);
            }
        }
        catch (Exception e) {
            LOG.warn("Failed to print LSP traffic to the console", e);
        }
    }

    protected void logStdErr(CharSequence message) {
        LanguageServiceLogger logger = myLsCommunicationLogger;
        if (logger != null) {
            logger.logError(message);
        }
    }
}

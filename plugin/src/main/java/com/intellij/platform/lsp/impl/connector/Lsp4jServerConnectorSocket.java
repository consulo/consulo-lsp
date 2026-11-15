package com.intellij.platform.lsp.impl.connector;

import com.intellij.platform.lsp.api.LspCommunicationChannel;
import com.intellij.platform.lsp.impl.LspClientImpl;
import consulo.logging.Logger;
import consulo.process.ExecutionException;
import consulo.process.ProcessHandler;
import consulo.process.ProcessOutputType;
import consulo.process.event.ProcessEvent;
import consulo.util.dataholder.Key;
import org.jspecify.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;

public class Lsp4jServerConnectorSocket extends Lsp4jServerConnector {
    private static final Logger LOG = Logger.getInstance(Lsp4jServerConnectorSocket.class);

    private final LspClientImpl myLspClient;
    private volatile @Nullable Socket mySocket;
    private volatile @Nullable DataOutputStream myOutputStream;
    private volatile @Nullable DataInputStream myInputStream;
    private final @Nullable ProcessHandler myProcessHandler;
    private final LspCommunicationChannel.Socket mySocketInfo;

    public Lsp4jServerConnectorSocket(LspClientImpl lspClient) throws ExecutionException {
        super(lspClient);
        myLspClient = lspClient;
        mySocketInfo = (LspCommunicationChannel.Socket) lspClient.getDescriptor().getLspCommunicationChannel();

        if (mySocketInfo.getStartProcess()) {
            ProcessHandler processHandler = lspClient.getDescriptor().startServerProcess();
            processHandler.addProcessListener(new LspServerProcessListenerBase(lspClient) {
                @Override
                @SuppressWarnings("rawtypes")
                public void onTextAvailable(ProcessEvent event, Key outputType) {
                    // The server talks LSP over the socket, so both stdout and stderr are its log
                    printProcessOutputToConsole(event, outputType);
                    String text = event.getText().stripTrailing();
                    if (text.isEmpty()) {
                        return;
                    }
                    myLspClient.logInfo((ProcessOutputType.isStderr(outputType) ? "STDERR" : "STDOUT") + ": " + event.getText());
                    if (ProcessOutputType.isStderr(outputType)) {
                        myLspClient.appendServerErrorOutput(text);
                        logStdErr(text);
                    }
                }
            });
            processHandler.startNotify();
            myProcessHandler = processHandler;
        }
        else {
            myProcessHandler = null;
        }
    }

    @Override
    protected OutputStream getIdeToServerStream() {
        DataOutputStream outputStream = myOutputStream;
        return outputStream != null ? outputStream : OutputStream.nullOutputStream();
    }

    @Override
    protected InputStream getServerToIdeStream() {
        DataInputStream inputStream = myInputStream;
        return inputStream != null ? inputStream : InputStream.nullInputStream();
    }

    /**
     * Must be called on a background thread without a read lock.
     */
    @Override
    protected void prepareConnect() throws Exception {
        Throwable lastException = null;

        ProcessHandler processHandler = myProcessHandler;
        if (mySocketInfo.getStartProcess() && processHandler != null) {
            for (int i = 1; i <= 10; i++) {
                // Consulo: ProcessHandler does not expose the java.lang.Process, `!isProcessTerminated()` replaces `process.isAlive`
                if (processHandler.isProcessTerminated() || mySocket != null) {
                    break;
                }
                try {
                    connectSocket();
                    lastException = null;
                    break;
                }
                catch (Throwable e) {
                    lastException = e;
                    myLspClient.logDebug("Attempt " + i + ": failed to create a socket connection (port=" + mySocketInfo.getPort() + "): " + e.getMessage());
                    Thread.sleep(1000);
                }
            }
        }
        else {
            connectSocket();
            lastException = null;
        }

        if (lastException != null) {
            myLspClient.logWarn("All attempts to create a socket connection failed (port=" + mySocketInfo.getPort() + ")");
            if (lastException instanceof Exception exception) {
                throw exception;
            }
            throw (Error) lastException;
        }
    }

    private void connectSocket() throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress().getHostAddress(), mySocketInfo.getPort());
        mySocket = socket;
        myOutputStream = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        myInputStream = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
    }

    @Override
    protected boolean isConnectionAlive() {
        Socket socket = mySocket;
        if (socket == null || myOutputStream == null || myInputStream == null || socket.isClosed()) {
            return false;
        }
        if (mySocketInfo.getStartProcess()) {
            ProcessHandler processHandler = myProcessHandler;
            return processHandler != null && processHandler.isStartNotified() && !processHandler.isProcessTerminated();
        }
        return true;
    }

    @Override
    protected void disconnect() {
        Socket socket = mySocket;
        if (socket != null && !socket.isClosed()) {
            // outputStream and inputStream are automatically closed with socket
            try {
                socket.close();
            }
            catch (IOException e) {
                LOG.warn(e);
            }
        }

        ProcessHandler processHandler = myProcessHandler;
        if (processHandler != null) {
            Lsp4jServerConnectorUtilKt.stopProcess(processHandler, myLspClient);
        }
    }
}

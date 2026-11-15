package com.intellij.platform.lsp.impl.connector;

import com.intellij.platform.lsp.impl.LspClientImpl;
import consulo.logging.Logger;
import consulo.process.ExecutionException;
import consulo.process.ProcessHandler;
import consulo.process.ProcessOutputType;
import consulo.process.event.ProcessEvent;
import consulo.util.dataholder.Key;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class Lsp4jServerConnectorStdio extends Lsp4jServerConnector {
    private final LspClientImpl myLspClient;
    private final ProcessHandler myProcessHandler;
    private final LspServerProcessListener myProcessListener;
    private final OutputStream myIdeToServerStream;
    private final InputStream myServerToIdeStream;

    public Lsp4jServerConnectorStdio(LspClientImpl lspClient) throws ExecutionException, IOException {
        super(lspClient);
        myLspClient = lspClient;
        myProcessHandler = lspClient.getDescriptor().startServerProcess();

        myProcessListener = new LspServerProcessListener(lspClient, this::logStdErr);
        myProcessHandler.addProcessListener(myProcessListener);
        myProcessHandler.startNotify();

        OutputStream processInput = myProcessHandler.getProcessInput();
        if (processInput == null) {
            throw new IOException("The process handler of the LSP server has no process input: " + myProcessHandler);
        }
        myIdeToServerStream = processInput;
        myServerToIdeStream = myProcessListener.getPipedInputStream();
    }

    @Override
    protected OutputStream getIdeToServerStream() {
        return myIdeToServerStream;
    }

    @Override
    protected InputStream getServerToIdeStream() {
        return myServerToIdeStream;
    }

    @Override
    protected void prepareConnect() {
    }

    @Override
    protected boolean isConnectionAlive() {
        return myProcessHandler.isStartNotified() && !myProcessHandler.isProcessTerminated();
    }

    @Override
    protected void releaseServerToIdeStream() {
        myProcessListener.stopStreamForwarding();
    }

    @Override
    protected void disconnect() {
        // Release serverToIdeStream before waiting for the process to stop: otherwise the process output
        // reader may be blocked writing into a full buffer, `processTerminated` would never fire, and the process
        // (and its threads) would leak (IJPL-250254).
        myProcessListener.stopStreamForwarding();
        Lsp4jServerConnectorUtilKt.stopProcess(myProcessHandler, myLspClient);
    }

    private static final class LspServerProcessListener extends LspServerProcessListenerBase {
        private static final Logger LOG = Logger.getInstance(LspServerProcessListener.class);

        private final LspClientImpl myLspClient;
        private final Consumer<String> myLogStdErr;
        private final PipedOutputStream myPipedOutputStream;
        private final OutputStreamWriter myOutputStreamWriter;
        private final PipedInputStream myPipedInputStream;

        /**
         * Set once serverToIdeStream has been closed on purpose, so that a resulting write failure is expected, not an error.
         */
        private volatile boolean myStreamForwardingStopped = false;

        LspServerProcessListener(LspClientImpl lspClient, Consumer<String> logStdErr) throws IOException {
            super(lspClient);
            myLspClient = lspClient;
            myLogStdErr = logStdErr;
            myPipedOutputStream = new PipedOutputStream();
            myOutputStreamWriter = new OutputStreamWriter(myPipedOutputStream, StandardCharsets.UTF_8);
            myPipedInputStream = new PipedInputStream(myPipedOutputStream);
        }

        PipedInputStream getPipedInputStream() {
            return myPipedInputStream;
        }

        /**
         * Stops re-buffering the server output into serverToIdeStream. Closing the write side immediately wakes
         * both a process output reader blocked in {@code PipedInputStream.awaitSpace} on a full buffer (it gets an
         * {@link IOException}) and the LSP listener blocked in {@code read} (it gets EOF). We close the underlying stream
         * ({@link #myPipedOutputStream}) directly rather than the wrapping {@link #myOutputStreamWriter}, whose {@code close} would flush first
         * and could itself block on the full buffer; closing the stream does not flush, so it cannot block. Safe to
         * call repeatedly and from any thread (closing an already-closed stream is a no-op). Without this, a stalled
         * reader lets the output reader block forever, {@code processTerminated} never fires, and the whole process leaks (IJPL-250254).
         */
        void stopStreamForwarding() {
            myStreamForwardingStopped = true;
            closeQuietly(myPipedOutputStream);
        }

        @Override
        @SuppressWarnings("rawtypes")
        public void onTextAvailable(ProcessEvent event, Key outputType) {
            if (ProcessOutputType.isStdout(outputType)) {
                if (myStreamForwardingStopped) {
                    // Forwarding was already stopped on purpose because the LSP listener stopped reading: nothing would
                    // drain a write and there is nothing to flush, so skip the write entirely. Drop the trailing server
                    // output and make sure the process is going down (IJPL-250254).
                    myLspClient.logInfo("Dropping server output received after the listener stopped");
                    Lsp4jServerConnectorUtilKt.stopProcess(event.getProcessHandler(), myLspClient);
                    return;
                }
                try {
                    myOutputStreamWriter.write(event.getText());
                    myOutputStreamWriter.flush();
                }
                catch (IOException e) {
                    if (myStreamForwardingStopped) {
                        // Forwarding was stopped on another thread while this write was in flight — the same expected case
                        // as the up-front check above, only observed as a write failure. Drop the trailing output.
                        myLspClient.logInfo("Dropping server output received after the listener stopped: " + e.getMessage());
                    }
                    else {
                        // java.base internals of PipedInputStream are not reflectively accessible, so only the public state is reported
                        String debugInfo = "available=" + safeAvailable();
                        myLspClient.logError("Problem proxying data to the listener: " + e.getMessage() + "\n" + debugInfo);
                    }
                    Lsp4jServerConnectorUtilKt.stopProcess(event.getProcessHandler(), myLspClient);
                }
            }
            else if (ProcessOutputType.isStderr(outputType)) {
                printProcessOutputToConsole(event, outputType);
                String text = event.getText().stripTrailing();
                if (text.isEmpty()) {
                    return;
                }
                myLspClient.logInfo("STDERR: " + text);
                myLspClient.appendServerErrorOutput(text);
                myLogStdErr.accept(text);
            }
        }

        private String safeAvailable() {
            try {
                return String.valueOf(myPipedInputStream.available());
            }
            catch (IOException e) {
                return e.toString();
            }
        }

        @Override
        public void processTerminated(ProcessEvent event) {
            // No more stdout is expected after termination, but set the flag first anyway: closing the stream below
            // makes any racing write fail, and we want that treated as expected output rather than logged as an error.
            myStreamForwardingStopped = true;
            // outputStreamWriter goes through closeQuietly, not a plain close(): once stopStreamForwarding has closed
            // the stream, the writer's flush throws — an expected failure here.
            closeQuietly(myOutputStreamWriter, myPipedOutputStream, myPipedInputStream);
            super.processTerminated(event);
        }

        private static void closeQuietly(Closeable... closeables) {
            for (Closeable closeable : closeables) {
                try {
                    closeable.close();
                }
                catch (IOException e) {
                    LOG.debug("Failed to close " + closeable.getClass().getSimpleName(), e);
                }
            }
        }
    }
}

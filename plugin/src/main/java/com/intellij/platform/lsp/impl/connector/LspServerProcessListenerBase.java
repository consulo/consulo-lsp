package com.intellij.platform.lsp.impl.connector;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import consulo.application.ReadAction;
import consulo.process.event.ProcessEvent;
import consulo.process.event.ProcessListener;
import consulo.util.dataholder.Key;

public class LspServerProcessListenerBase implements ProcessListener {
    private final LspClientImpl myLspClient;

    public LspServerProcessListenerBase(LspClientImpl lspClient) {
        myLspClient = lspClient;
    }

    @Override
    public void startNotified(ProcessEvent event) {
        myLspClient.logInfo("LSP server process started: " + event.getProcessHandler());
    }

    @Override
    public void processTerminated(ProcessEvent event) {
        String text = "Exit code: " + event.getExitCode() + "\nCommand line: " + event.getProcessHandler();
        myLspClient.logInfo("LSP server process terminated. " + text);

        // The process handler calls processTerminated after it has read all the output, so no output comes after the flush
        // TODO serviceView stage: console()?.flushProcessOutput()

        LspClientManagerImpl manager = ReadAction.compute(
            () -> !myLspClient.getProject().isDisposed() ? LspClientManagerImpl.getInstanceImpl(myLspClient.getProject()) : null
        );
        if (manager != null) {
            manager.handleMaybeUnexpectedServerStop(myLspClient, text);
        }
    }

    /**
     * Prints a raw chunk of the server process output to the console of the client in the Services tool window.
     * Give it each chunk without a change: the console puts the chunks together into lines.
     */
    protected void printProcessOutputToConsole(ProcessEvent event, Key<?> outputType) {
        // TODO serviceView stage: LspServiceViewSupport.getInstance(project).getOrCreateConsole(lspClient)?.printProcessOutput(event.text, outputType)
    }
}

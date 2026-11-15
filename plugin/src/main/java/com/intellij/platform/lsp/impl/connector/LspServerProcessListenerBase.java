package com.intellij.platform.lsp.impl.connector;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.serviceView.LspClientConsole;
import com.intellij.platform.lsp.impl.serviceView.LspServiceViewSupport;
import consulo.application.ReadAction;
import consulo.process.event.ProcessEvent;
import consulo.process.event.ProcessListener;
import consulo.project.Project;
import consulo.util.dataholder.Key;
import org.jspecify.annotations.Nullable;

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
        LspClientConsole flushConsole = console();
        if (flushConsole != null) {
            flushConsole.flushProcessOutput();
        }

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
        LspClientConsole console = console();
        if (console != null) {
            console.printProcessOutput(event.getText(), outputType);
        }
    }

    private @Nullable LspClientConsole console() {
        Project project = myLspClient.getProject();
        return project.isDisposed() ? null : LspServiceViewSupport.getInstance(project).getOrCreateConsole(myLspClient);
    }
}

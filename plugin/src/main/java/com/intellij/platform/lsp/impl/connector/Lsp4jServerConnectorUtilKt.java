package com.intellij.platform.lsp.impl.connector;

import com.intellij.platform.lsp.impl.LspClientImpl;
import consulo.process.ProcessHandler;
import consulo.process.ProcessHandlerStopper;

public final class Lsp4jServerConnectorUtilKt {
    private Lsp4jServerConnectorUtilKt() {
    }

    public static void stopProcess(ProcessHandler processHandler, LspClientImpl lspClient) {
        if (!processHandler.isProcessTerminated()) {
            lspClient.logInfo("Stopping LSP server process: " + processHandler);
            ProcessHandlerStopper.stop(processHandler);
            if (!processHandler.waitFor(1000)) {
                // ProcessHandlerStopper.stop first tries to shut down the process gracefully,
                // but sometimes the process ignores the CTRL+C signal.
                // So, if the process is not finished within a second,
                // the second call to ProcessHandlerStopper.stop will kill it if it is a KillableProcessHandler.
                lspClient.logInfo("Failed to stop LSP server process gracefully in 1 second. Killing process: " + processHandler);
                ProcessHandlerStopper.stop(processHandler);
            }
        }
    }
}

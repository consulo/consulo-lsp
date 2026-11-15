package com.intellij.platform.lsp.impl.features;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspCoroutineScopeService;
import consulo.application.progress.ProgressManager;
import consulo.application.util.Semaphore;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;

public final class LspExecuteCommandKt {
    private LspExecuteCommandKt() {
    }

    /**
     * Sends a <a href="https://microsoft.github.io/language-server-protocol/specification/#workspace_executeCommand">workspace/executeCommand</a>
     * request to the server and waits for whichever comes first:
     * <ul>
     *  <li>a {@code workspace/applyEdit} request from the server - in this case this function returns the corresponding {@link WorkspaceEdit},
     *  and it is up to the caller to apply it;</li>
     *  <li>a response to the {@code executeCommand} request (any response, including an error) - in this case this function returns {@code null}.</li>
     * </ul>
     * <p>
     * Waiting is cancellable, so this function is safe to call inside a cancellable read action.
     * Must be called on a background thread.
     */
    public static @Nullable WorkspaceEdit executeCommandExpectingWorkspaceEdit(LspClientImpl lspClient, Command command) {
        // Released as soon as either the server sends a `workspace/applyEdit` request or the `executeCommand` request gets a response.
        Semaphore semaphore = new Semaphore(1);
        AtomicReference<@Nullable WorkspaceEdit> workspaceEdit = new AtomicReference<>();

        try {
            lspClient.getServerNotificationsHandler().setNextApplyEditHandler(edit -> {
                workspaceEdit.set(edit);
                semaphore.up();
            });

            LspCoroutineScopeService.getInstance(lspClient.getProject()).launch(() -> {
                try {
                    lspClient.sendRequest(it -> it.getWorkspaceService().executeCommand(new ExecuteCommandParams(command.getCommand(), command.getArguments())))
                        .whenComplete((result, throwable) -> semaphore.up());
                }
                catch (Throwable t) {
                    semaphore.up();
                    throw t;
                }
            });

            while (!semaphore.waitFor(10)) {
                ProgressManager.checkCanceled();
            }
        }
        finally {
            lspClient.getServerNotificationsHandler().setNextApplyEditHandler(null);
        }

        return workspaceEdit.get();
    }
}

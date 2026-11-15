// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspServer;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.ExecuteCommandParams;

/**
 * Handles <a href="https://microsoft.github.io/language-server-protocol/specification#command">Command</a> objects received from the LSP server.
 */
public non-sealed class LspCommandsSupport extends LspCommandsCustomizer {
    /**
     * Handles <a href="https://microsoft.github.io/language-server-protocol/specification#command">Command</a> objects received from the LSP server.
     * <p>
     * The default implementation of this function just sends the
     * <a href="https://microsoft.github.io/language-server-protocol/#workspace_executeCommand">workspace/executeCommand</a>
     * request to the LSP server.
     * Plugins may override this function to implement some plugin-specific handling of some commands.
     * <p>
     * In the spec v.3.17 there are four responses that may include {@link Command} object:
     * <ul>
     * <li><a href="https://microsoft.github.io/language-server-protocol/specification#codeAction">CodeAction</a>,
     * for example, a quick fix, an intention action or a refactoring</li>
     * <li><a href="https://microsoft.github.io/language-server-protocol/specification#completionItem">CompletionItem</a></li>
     * <li><a href="https://microsoft.github.io/language-server-protocol/specification#inlayHintLabelPart">InlayHintLabelPart</a></li>
     * <li><a href="https://microsoft.github.io/language-server-protocol/specification#codeLens">CodeLens</a></li>
     * </ul>
     * Note that this function is called in the Event Dispatch Thread.
     * Implementations that perform time-consuming tasks should switch to
     * a background thread, for example, using {@link consulo.application.Application#executeOnPooledThread(Runnable)}
     */
    @RequiredUIAccess
    @SuppressWarnings("deprecation")
    public void executeCommand(LspClient lspClient, VirtualFile contextFile, Command command) {
        executeCommand((LspServer) lspClient, contextFile, command);
    }

    /**
     * @deprecated Override or call {@link #executeCommand(LspClient, VirtualFile, Command)} — the LspClient overload
     */
    @Deprecated
    @RequiredUIAccess
    @SuppressWarnings("deprecation")
    public void executeCommand(LspServer server, VirtualFile contextFile, Command command) {
        server.sendNotification(it -> it.getWorkspaceService().executeCommand(new ExecuteCommandParams(command.getCommand(), command.getArguments())));
    }
}

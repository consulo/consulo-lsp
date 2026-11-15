// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.LogTraceParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ShowDocumentParams;
import org.eclipse.lsp4j.ShowDocumentResult;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentContentRefreshParams;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkspaceFolder;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Plugins don't need to use this interface.
 * <p>
 * Its internal implementation handles all standard (documented in the official LSP specification) requests and notifications
 * that the LSP server sends to the IDE.
 */
public interface LspServerNotificationsHandler {
    CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params);

    CompletableFuture<Void> registerCapability(RegistrationParams params);

    CompletableFuture<Void> unregisterCapability(UnregistrationParams params);

    void telemetryEvent(Object object);

    void publishDiagnostics(PublishDiagnosticsParams params);

    void showMessage(MessageParams params);

    CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params);

    CompletableFuture<ShowDocumentResult> showDocument(ShowDocumentParams params);

    void logMessage(MessageParams params);

    CompletableFuture<List<WorkspaceFolder>> workspaceFolders();

    CompletableFuture<List<@Nullable Object>> configuration(ConfigurationParams params);

    CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params);

    void notifyProgress(ProgressParams params);

    void logTrace(LogTraceParams params);

    CompletableFuture<Void> refreshSemanticTokens();

    CompletableFuture<Void> refreshCodeLenses();

    CompletableFuture<Void> refreshInlayHints();

    CompletableFuture<Void> refreshInlineValues();

    CompletableFuture<Void> refreshDiagnostics();

    default CompletableFuture<Void> refreshTextDocumentContent(TextDocumentContentRefreshParams params) {
        return CompletableFuture.completedFuture(null);
    }
}

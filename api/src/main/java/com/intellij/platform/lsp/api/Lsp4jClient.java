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
import org.eclipse.lsp4j.services.LanguageClient;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Implementation of the {@link org.eclipse.lsp4j.services.LanguageClient} interface.
 * It handles all standard requests and notifications that the LSP server sends to the IDE.
 * 'Standard' requests and notifications are the ones that are documented
 * in the official <a href="https://microsoft.github.io/language-server-protocol/specification">LSP specification</a>.
 * <p>
 * To handle custom undocumented requests/notifications from the server, plugins need to override {@link LspClientDescriptor#createLsp4jClient}
 * and return their subclass of this {@link Lsp4jClient}. This subclass should contain specially annotated functions, which will be called
 * via reflection by the {@code lsp4j} library once the corresponding request/notification arrives from the LSP server.
 * <p>
 * Example:
 * <pre>
 *    &#64;JsonNotification("&#64;/foo/bar")
 *    public void fooBar(FooBarNotification fooBar) { ... }
 * </pre>
 *
 * @see LspClientDescriptor#createLsp4jClient
 */
public class Lsp4jClient implements LanguageClient {
    private final LspServerNotificationsHandler myServerNotificationsHandler;

    public Lsp4jClient(LspServerNotificationsHandler serverNotificationsHandler) {
        myServerNotificationsHandler = serverNotificationsHandler;
    }

    @Override
    public final CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
        return myServerNotificationsHandler.applyEdit(params);
    }

    @Override
    public final CompletableFuture<Void> registerCapability(RegistrationParams params) {
        return myServerNotificationsHandler.registerCapability(params);
    }

    @Override
    public final CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
        return myServerNotificationsHandler.unregisterCapability(params);
    }

    @Override
    public void telemetryEvent(Object object) {
        myServerNotificationsHandler.telemetryEvent(object);
    }

    @Override
    public final void publishDiagnostics(PublishDiagnosticsParams params) {
        myServerNotificationsHandler.publishDiagnostics(params);
    }

    @Override
    public final void showMessage(MessageParams params) {
        myServerNotificationsHandler.showMessage(params);
    }

    @Override
    public final CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
        return myServerNotificationsHandler.showMessageRequest(params);
    }

    @Override
    public final CompletableFuture<ShowDocumentResult> showDocument(ShowDocumentParams params) {
        return myServerNotificationsHandler.showDocument(params);
    }

    @Override
    public final void logMessage(MessageParams params) {
        myServerNotificationsHandler.logMessage(params);
    }

    @Override
    public final CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
        return myServerNotificationsHandler.workspaceFolders();
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public final CompletableFuture<List<Object>> configuration(ConfigurationParams params) {
        // the result contains `null` for unknown sections, the lsp4j signature has no nullability
        return (CompletableFuture) myServerNotificationsHandler.configuration(params);
    }

    @Override
    public final CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
        return myServerNotificationsHandler.createProgress(params);
    }

    @Override
    public final void notifyProgress(ProgressParams params) {
        myServerNotificationsHandler.notifyProgress(params);
    }

    @Override
    public final void logTrace(LogTraceParams params) {
        myServerNotificationsHandler.logTrace(params);
    }

    @Override
    public final CompletableFuture<Void> refreshSemanticTokens() {
        return myServerNotificationsHandler.refreshSemanticTokens();
    }

    @Override
    public final CompletableFuture<Void> refreshCodeLenses() {
        return myServerNotificationsHandler.refreshCodeLenses();
    }

    @Override
    public final CompletableFuture<Void> refreshInlayHints() {
        return myServerNotificationsHandler.refreshInlayHints();
    }

    @Override
    public final CompletableFuture<Void> refreshInlineValues() {
        return myServerNotificationsHandler.refreshInlineValues();
    }

    @Override
    public final CompletableFuture<Void> refreshDiagnostics() {
        return myServerNotificationsHandler.refreshDiagnostics();
    }

    @Override
    public final CompletableFuture<Void> refreshTextDocumentContent(TextDocumentContentRefreshParams params) {
        return myServerNotificationsHandler.refreshTextDocumentContent(params);
    }
}

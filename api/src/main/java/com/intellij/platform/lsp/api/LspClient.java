// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import consulo.document.Document;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The IDE's model of a started LSP server. In <a href="https://microsoft.github.io/language-server-protocol/">LSP terminology</a> the IDE is the
 * client, which is why this IDE-side model is named {@link LspClient} rather than after the server.
 * <p>
 * To get an instance of {@link LspClient} use {@link LspClientManager#getClients}
 */
public interface LspClient {
    int DEFAULT_REQUEST_TIMEOUT_MS = 10_000;

    Class<? extends LspIntegrationProvider> getProviderClass();

    Project getProject();

    /**
     * An {@link LspClientDescriptor} that is used to start and control the behavior of this {@link LspClient}.
     * The returned object is exactly the one that the plugin passed to {@link LspIntegrationProvider.LspClientStarter#ensureClientStarted}.
     */
    LspClientDescriptor getDescriptor();

    LspServerState getState();

    @Nullable InitializeResult getInitializeResult();

    /**
     * Sends a <a href="https://microsoft.github.io/language-server-protocol/specification/#notificationMessage">notification</a>
     * from the IDE to the LSP server.
     * <p>
     * Example:
     * <pre>
     *    lspClient.sendNotification(it -> it.getWorkspaceService().didChangeConfiguration(new DidChangeConfigurationParams(...)));
     * </pre>
     * Custom (undocumented) notification example:
     * <pre>
     *    lspClient.sendNotification(it -> ((FooLsp4jServer) it).customNotification(...));
     * </pre>
     *
     * @see LspClientDescriptor#getLsp4jServerClass()
     */
    void sendNotification(Consumer<Lsp4jServer> lsp4jSender);

    /**
     * Drops everything the IDE has cached from this server — pulled diagnostics, inlay hints, semantic tokens,
     * hovers, symbols, … — and re-requests it for the opened files.
     * <p>
     * Call it after changing what the server bases its answers on, a settings push for example: every pulled result is
     * cached until the file changes, so without this the server's new answers are only visible after an edit. Send the
     * push first: the re-requests are ordered after everything already sent to the server.
     * <p>
     * What is on screen stays there until the fresh results arrive, so there is no flicker. Note that this cannot
     * guarantee the server has finished applying the change before it answers; a server that re-reads its
     * configuration asynchronously should also send the matching {@code workspace/.../refresh} request once it has.
     */
    void invalidateServerResults();

    /**
     * Sends a request to the LSP server.
     * <p>
     * The returned future is completed with {@code null} if the LSP server:
     * <ul>
     *  <li>is not yet initialized or is already shut down</li>
     *  <li>returns a {@code null} response</li>
     * </ul>
     * <p>
     * This function has no timeout. To limit the wait, use {@link CompletableFuture#completeOnTimeout} or
     * {@link CompletableFuture#orTimeout}. Cancelling the returned future cancels the request.
     * <p>
     * Example:
     * <pre>
     *    lspClient.sendRequest(it -> it.getWorkspaceService().executeCommand(new ExecuteCommandParams(...)));
     * </pre>
     * Custom (undocumented) request example:
     * <pre>
     *    lspClient.sendRequest(it -> ((FooLsp4jServer) it).customRequest(...));
     * </pre>
     * <p>
     * The future is completed exceptionally with {@link org.eclipse.lsp4j.jsonrpc.ResponseErrorException} if the LSP server
     * responds with an error. The error does not appear in the IDE logs.
     *
     * @see LspClientDescriptor#getLsp4jServerClass()
     */
    <Lsp4jResponse> CompletableFuture<@Nullable Lsp4jResponse> sendRequest(Function<Lsp4jServer, CompletableFuture<Lsp4jResponse>> lsp4jSender);

    /**
     * Sends a request to the LSP server and waits for the response synchronously, {@link #DEFAULT_REQUEST_TIMEOUT_MS} at most.
     *
     * @see #sendRequestSync(int, Function)
     */
    default <Lsp4jResponse> @Nullable Lsp4jResponse sendRequestSync(Function<Lsp4jServer, CompletableFuture<Lsp4jResponse>> lsp4jSender) {
        return sendRequestSync(DEFAULT_REQUEST_TIMEOUT_MS, lsp4jSender);
    }

    /**
     * Sends a request to the LSP server and waits for the response synchronously.
     * <p>
     * Waiting is cancelable (thanks to regular {@link consulo.application.progress.ProgressManager#checkCanceled} calls).
     * Prefer the asynchronous function {@link #sendRequest} when possible.
     * Must be called in a background thread.
     * <p>
     * This function will return {@code null} if the LSP server:
     * <ul>
     *  <li>is not yet initialized or is already shut down</li>
     *  <li>doesn't send any response within {@code timeoutMs} milliseconds (10 seconds by default)</li>
     *  <li>returns a {@code null} response</li>
     *  <li>responds with an error (the error will appear in the IDE logs)</li>
     * </ul>
     * <p>
     * Example:
     * <pre>
     *    Object result = lspClient.sendRequestSync(it -> it.getWorkspaceService().executeCommand(new ExecuteCommandParams(...)));
     * </pre>
     * Custom (undocumented) request example:
     * <pre>
     *    CustomResult result = lspClient.sendRequestSync(it -> ((FooLsp4jServer) it).customRequest(...));
     * </pre>
     *
     * @see LspClientDescriptor#getLsp4jServerClass()
     */
    <Lsp4jResponse> @Nullable Lsp4jResponse sendRequestSync(int timeoutMs, Function<Lsp4jServer, CompletableFuture<Lsp4jResponse>> lsp4jSender);

    /**
     * Creates <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocumentIdentifier">TextDocumentIdentifier</a>
     * for the given {@code file} to be used in various LSP requests.
     */
    TextDocumentIdentifier getDocumentIdentifier(VirtualFile file);

    /**
     * Returns a text document version as specified by, for example,
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocumentItem">TextDocumentItem</a>,
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#versionedTextDocumentIdentifier">VersionedTextDocumentIdentifier</a>,
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#optionalVersionedTextDocumentIdentifier">OptionalVersionedTextDocumentIdentifier</a>,
     * or <a href="https://microsoft.github.io/language-server-protocol/specification/#publishDiagnosticsParams">PublishDiagnosticsParams</a>
     */
    int getDocumentVersion(Document document);

    /**
     * Advances and returns the version to declare for {@code document}'s next {@code didOpen}/{@code didChange}. This client
     * owns the number outright, so callers that must declare a version before an edit lands -- an incremental
     * change's line numbers have to be computed from the pre-edit document, for instance -- can call this
     * safely instead of trying to predict {@code document}'s own modification-tracking state.
     */
    int nextDocumentVersion(Document document);
}

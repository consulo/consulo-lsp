package com.intellij.platform.lsp.impl;

import com.intellij.platform.lsp.api.Lsp4jServer;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspServerState;
import consulo.application.progress.ProgressManager;
import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.component.ProcessCanceledException;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

public abstract class LspRequestExecutorBase {
    /**
     * {@code ConcurrencyUtil.DEFAULT_TIMEOUT_MS} upstream
     */
    private static final long DEFAULT_TIMEOUT_MS = 10;

    private final LspClientImpl myLspClient;
    private final ExecutorService myExecutorService;

    protected LspRequestExecutorBase(LspClientImpl lspClient) {
        myLspClient = lspClient;
        myExecutorService = AppExecutorUtil.createBoundedApplicationPoolExecutor("LSP Executor: " + lspClient.getDescriptor(), 1);
    }

    public void sendNotification(Consumer<Lsp4jServer> lsp4jSender) {
        synchronized (myExecutorService) {
            if (!myExecutorService.isShutdown()) {
                myExecutorService.execute(() -> lsp4jSender.accept(myLspClient.getLsp4jServer()));
            }
        }
    }

    /**
     * Upstream: {@code suspend fun sendRequest(...)}. The returned future completes with {@code null} if the server is not running.
     * Cancelling the returned future sends {@code $/cancelRequest} to the server.
     */
    public <Lsp4jResponse> CompletableFuture<@Nullable Lsp4jResponse> sendRequest(Function<Lsp4jServer, CompletableFuture<Lsp4jResponse>> lsp4jSender) {
        CompletableFuture<@Nullable Lsp4jResponse> future = doSendRequestAsync(lsp4jSender);
        return future != null ? future : CompletableFuture.completedFuture(null);
    }

    /**
     * Must be called on a background thread.
     */
    public <Lsp4jResponse> @Nullable Lsp4jResponse sendRequestSync(Function<Lsp4jServer, CompletableFuture<Lsp4jResponse>> lsp4jSender) {
        return sendRequestSync(LspClient.DEFAULT_REQUEST_TIMEOUT_MS, lsp4jSender);
    }

    /**
     * Must be called on a background thread.
     */
    public <Lsp4jResponse> @Nullable Lsp4jResponse sendRequestSync(
        int timeoutMs,
        Function<Lsp4jServer, CompletableFuture<Lsp4jResponse>> lsp4jSender
    ) {
        CompletableFuture<@Nullable Lsp4jResponse> future = doSendRequestAsync(lsp4jSender);
        return future != null ? awaitWithCheckCanceled(future, true, lsp4jSender.getClass().getName(), timeoutMs) : null;
    }

    /**
     * Must be called on a background thread.
     */
    protected <T> @Nullable T awaitSync(CompletableFuture<@Nullable T> future) {
        return awaitSync(future, LspClient.DEFAULT_REQUEST_TIMEOUT_MS);
    }

    /**
     * Must be called on a background thread.
     */
    protected <T> @Nullable T awaitSync(CompletableFuture<@Nullable T> future, int timeoutMs) {
        return awaitWithCheckCanceled(future, true, "awaitSync", timeoutMs);
    }

    /**
     * This function sends a request to the LSP server and tries to wait for the response synchronously. If synchronous waiting gets
     * canceled, the function switches to the asynchronous mode. In more detail:
     * <ul>
     *  <li>Sends a request to the LSP server and waits for the response for no more than {@link LspClient#DEFAULT_REQUEST_TIMEOUT_MS} ms.
     *    Waiting is cancelable (thanks to regular {@link ProgressManager#checkCanceled} calls)</li>
     *  <li>If synchronous waiting is canceled, this function returns immediately, but keeps waiting for the response in background</li>
     *  <li>Once a response is received, it's passed to the {@code lsp4jResponseConsumer}</li>
     *  <li>In several cases the {@code lsp4jResponseConsumer} may receive null:
     *    this {@code LspServer} is not running or not initialized yet;
     *    server returns {@code null} response;
     *    server responds with an error (the error will appear in IDE logs)</li>
     * </ul>
     * <p>
     *  The use case is the following: even if waiting for the response is canceled, later the client will need the response anyway.
     *  So this function, which switches to async mode, helps not to send the same request once again.
     */
    public <Lsp4jResponse> void sendRequestAsyncButWaitForResponseWithCheckCanceled(
        Function<Lsp4jServer, CompletableFuture<Lsp4jResponse>> lsp4jSender,
        Consumer<@Nullable Lsp4jResponse> lsp4jResponseConsumer
    ) {
        CompletableFuture<@Nullable Lsp4jResponse> future = doSendRequestAsyncAndConsumeResponse(lsp4jSender, lsp4jResponseConsumer);
        if (future != null) {
            awaitWithCheckCanceled(future, false, lsp4jSender.getClass().getName(), LspClient.DEFAULT_REQUEST_TIMEOUT_MS);
        }
    }

    /**
     * Calls {@link #doSendRequestAsync} and passes the result of the {@link CompletableFuture} to the {@code lsp4jResponseConsumer}.
     * <p>
     * *Note:* don't expose {@link CompletableFuture} as a return type to the public API.
     */
    private <Lsp4jResponse> @Nullable CompletableFuture<@Nullable Lsp4jResponse> doSendRequestAsyncAndConsumeResponse(
        Function<Lsp4jServer, CompletableFuture<Lsp4jResponse>> lsp4jSender,
        Consumer<@Nullable Lsp4jResponse> lsp4jResponseConsumer
    ) {
        CompletableFuture<@Nullable Lsp4jResponse> future = doSendRequestAsync(lsp4jSender);
        if (future == null) {
            lsp4jResponseConsumer.accept(null);
            return null;
        }
        return future.whenComplete((result, throwable) -> lsp4jResponseConsumer.accept(result));
    }

    /**
     * Sends a request to the LSP server asynchronously. Note that {@code null} is a valid response from the server, so
     * {@link CompletableFuture#get} may return {@code null}.
     * <p>
     * *Note:* don't expose {@link CompletableFuture} as a return type to the public API.
     *
     * @return {@code null} if the current state of this {@code LspServer} doesn't allow sending requests.
     */
    protected <Lsp4jResponse> @Nullable CompletableFuture<@Nullable Lsp4jResponse> doSendRequestAsync(
        Function<Lsp4jServer, CompletableFuture<Lsp4jResponse>> lsp4jSender
    ) {
        if (myLspClient.getState() != LspServerState.Running) {
            myLspClient.logDebug("Server not initialized yet, skipping request " + lsp4jSender.getClass().getName());
            return null;
        }

        synchronized (myExecutorService) {
            if (myExecutorService.isShutdown()) {
                return null;
            }

            CompletableFuture<@Nullable Lsp4jResponse> resultFuture = new CompletableFuture<>();

            myExecutorService.execute(() -> {
                CompletableFuture<Lsp4jResponse> lsp4jResponseFuture;
                try {
                    lsp4jResponseFuture = lsp4jSender.apply(myLspClient.getLsp4jServer());
                }
                catch (Throwable t) {
                    resultFuture.completeExceptionally(t);
                    throw t;
                }

                lsp4jResponseFuture.whenComplete((Lsp4jResponse lsp4jResponse, @Nullable Throwable th) -> {
                    if (th == null) {
                        resultFuture.complete(lsp4jResponse);
                    }
                    else {
                        resultFuture.completeExceptionally(th);
                    }
                });

                // Propagate cancellation of the outer `resultFuture` to the inner `lsp4jResponseFuture`
                resultFuture.whenComplete((r, t) -> {
                    if (resultFuture.isCancelled()) {
                        sendCancelRequest(lsp4jResponseFuture);
                    }
                });
            });

            return resultFuture;
        }
    }

    /**
     * Sends <a href="https://microsoft.github.io/language-server-protocol/specification/#cancelRequest">$/cancelRequest</a>
     * notification to the LSP server
     *
     * @param lsp4jResponseFuture {@link CompletableFuture} returned by one of the functions of the {@link Lsp4jServer} class
     */
    private void sendCancelRequest(CompletableFuture<?> lsp4jResponseFuture) {
        synchronized (myExecutorService) {
            if (!myExecutorService.isShutdown()) {
                myExecutorService.execute(() -> lsp4jResponseFuture.cancel(true));
            }
        }
    }

    /**
     * Waits for the {@code future} completion for up to {@code timeoutMs} ms and returns its result.
     * Calls {@link ProgressManager#checkCanceled} every 10 ms.
     * <ul>
     *  <li>returns {@code null} if {@code future} doesn't complete in {@code timeoutMs} ms</li>
     *  <li>returns {@code null} if {@code future} completes exceptionally, writes the exception to the log file</li>
     *  <li>other exceptions are not handled, so thrown as is</li>
     * </ul>
     * Must be called on a background thread.
     *
     * @param cancelOnPCE whether {@link Future#cancel future.cancel()} should be called if {@link ProcessCanceledException} happens
     *                    while waiting for the server response
     */
    private <T> @Nullable T awaitWithCheckCanceled(Future<@Nullable T> future, boolean cancelOnPCE, String debugName, int timeoutMs) {
        long t0 = System.currentTimeMillis();

        while (System.currentTimeMillis() - t0 < timeoutMs) {
            try {
                ProgressManager.checkCanceled();
            }
            catch (ProcessCanceledException pce) {
                if (cancelOnPCE) {
                    future.cancel(true);
                }
                throw pce;
            }

            try {
                return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException ignored) {
                /* ok */
            }
            catch (ExecutionException e) {
                myLspClient.logWarn("Error response from server: " + e.getCause());
                return null;
            }
            catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        myLspClient.logInfo("No response from the server in " + timeoutMs + "ms for: " + debugName);
        future.cancel(true);
        return null;
    }

    public void shutdownNow() {
        synchronized (myExecutorService) {
            myExecutorService.shutdownNow();
        }

        afterShutdown();
    }

    public abstract void afterShutdown();
}

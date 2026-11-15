package com.intellij.platform.lsp.impl;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.disposer.Disposable;
import consulo.project.Project;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * A project-level {@link Disposable} of the LSP plugin (usable as a parent disposable,
 * e.g. {@code NonBlockingReadAction.expireWith}). {@link #launch}
 * runs the task on the application pool unless the project is already disposed.
 */
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
@Singleton
public class LspCoroutineScopeService implements Disposable {
    public static LspCoroutineScopeService getInstance(Project project) {
        return project.getInstance(LspCoroutineScopeService.class);
    }

    private volatile boolean myDisposed;

    @Inject
    public LspCoroutineScopeService() {
    }

    public boolean isDisposed() {
        return myDisposed;
    }

    /**
     * Replacement of {@code cs.launch { task() }}.
     */
    public Future<?> launch(Runnable task) {
        if (myDisposed) {
            return CompletableFuture.completedFuture(null);
        }
        return AppExecutorUtil.getAppExecutorService().submit(() -> {
            if (!myDisposed) {
                task.run();
            }
        });
    }

    /**
     * Replacement of {@code cs.future { task() }}.
     */
    public <T> CompletableFuture<T> future(Supplier<T> task) {
        if (myDisposed) {
            return CompletableFuture.failedFuture(new IllegalStateException("The project is disposed"));
        }
        return CompletableFuture.supplyAsync(task, AppExecutorUtil.getAppExecutorService());
    }

    @Override
    public void dispose() {
        myDisposed = true;
    }
}

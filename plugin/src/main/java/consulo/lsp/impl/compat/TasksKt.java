// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
/*
 * Copyright 2013-2026 consulo.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package consulo.lsp.impl.compat;

import consulo.application.progress.ProgressManager;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.lang.function.ThrowableSupplier;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * IntelliJ's {@code com.intellij.platform.ide.progress.runWithModalProgressBlocking} (progress/shared/src/tasks.kt) and the
 * {@code kotlinx.coroutines.future.await} that the coroutine-based callers use inside it. Consulo has no coroutine-based
 * modal progress.
 */
public final class TasksKt {
    private TasksKt() {
    }

    /**
     * Runs {@code action} in a background thread under a cancellable modal progress and returns its result.
     * Exceptions of {@code action} are rethrown as is.
     *
     * @throws consulo.application.progress.ProcessCanceledException if the user canceled the progress
     */
    @RequiredUIAccess
    public static <T extends @Nullable Object> T runWithModalProgressBlocking(Project project, LocalizeValue title, Supplier<T> action) {
        return ProgressManager.getInstance().runProcessWithProgressSynchronously(
            (ThrowableSupplier<T, RuntimeException>) action::get,
            title,
            true,
            project
        );
    }

    /**
     * {@code future.await()}: waits for {@code future} calling {@link ProgressManager#checkCanceled()} every 10 ms,
     * cancels {@code future} when the wait is canceled, and rethrows the exception that completed {@code future}
     * (a checked one wrapped in {@link CompletionException}).
     */
    public static <T extends @Nullable Object> T await(CompletableFuture<T> future) {
        while (true) {
            try {
                ProgressManager.checkCanceled();
            }
            catch (RuntimeException e) {
                future.cancel(true);
                throw e;
            }
            try {
                return future.get(10, TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException ignored) {
                // check cancellation and wait again
            }
            catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new CompletionException(cause);
            }
            catch (CancellationException e) {
                throw e;
            }
            catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        }
    }
}

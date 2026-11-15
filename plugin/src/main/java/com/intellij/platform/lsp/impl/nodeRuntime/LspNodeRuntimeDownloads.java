// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.nodeRuntime;

import com.intellij.platform.lsp.api.LspClientManager;
import com.intellij.platform.lsp.api.LspIntegrationProvider;
import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.ProgressManager;
import consulo.application.progress.Task;
import consulo.logging.Logger;
import consulo.project.Project;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads the Node.js runtime once, and restarts the clients of every provider that waits for it.
 * <p>
 * There is one pinned runtime, so there is one download. Only the restart is per provider, because
 * {@link LspClientManager#startClientsIfNeeded} takes one provider class.
 */
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
@Singleton
public class LspNodeRuntimeDownloads {
    private static final Logger LOG = Logger.getInstance(LspNodeRuntimeDownloads.class);

    private final Project myProject;

    /**
     * The providers whose clients start as soon as the runtime is ready.
     * <p>
     * {@code fileOpened} runs for every open file and for every provider, so a project with ten open files
     * adds the same provider ten times. A set of provider classes therefore holds at most one entry per
     * provider, and each one gets one restart.
     */
    private final Set<Class<? extends LspIntegrationProvider>> myProvidersToRestart = ConcurrentHashMap.newKeySet();

    /**
     * Whether a download task runs now. It guards the task, never the set.
     */
    private final AtomicBoolean myDownloadRunning = new AtomicBoolean(false);

    @Inject
    public LspNodeRuntimeDownloads(Project project) {
        myProject = project;
    }

    public static LspNodeRuntimeDownloads getInstance(Project project) {
        return project.getInstance(LspNodeRuntimeDownloads.class);
    }

    /**
     * Starts an LSP client that needs Node.js, and downloads the runtime first if it is absent.
     * <p>
     * {@link LspIntegrationProvider#fileOpened} cannot wait: it must not block the
     * UI, and its {@link LspIntegrationProvider.LspClientStarter} stops working the moment it returns. So
     * {@code startClient} is a thunk that runs on one of two paths:
     * <ul>
     * <li>The runtime is on disk. {@code startClient} runs now, inside the current {@code fileOpened} call, where the
     *   client starter still works.</li>
     * <li>The runtime is absent. This function drops {@code startClient} and joins the one background download.
     *   After the download it calls {@link LspClientManager#startClientsIfNeeded} for this provider, which calls
     *   {@code fileOpened} again for every open file. The second call takes the first path, and builds the
     *   descriptor again. Every provider shares that download, and each one gets its own restart.</li>
     * </ul>
     * <p>
     * The {@code fileOpened} documentation describes this two-call shape. This function is that shape, with the
     * state it needs kept in one place instead of in each provider.
     *
     * <pre>{@code
     * public void fileOpened(Project project, VirtualFile file, LspIntegrationProvider.LspClientStarter clientStarter) {
     *     if (isFooLspFile(file)) {
     *         LspNodeRuntimeDownloads.withNodeRuntimeEnsured(this, project, () ->
     *             clientStarter.ensureClientStarted(new FooLspServerDescriptor(project)));
     *     }
     * }
     * }</pre>
     * <p>
     * A descriptor started this way reads the runtime with {@link LspNodeRuntimeManager#getRuntime}, which
     * cannot be null on the path that runs {@code startClient}.
     *
     * @param project     the project the download progress belongs to, and the project whose clients restart
     * @param startClient starts the client. It runs at most once per {@code fileOpened} call, and only with the
     *                    runtime present.
     */
    public static void withNodeRuntimeEnsured(LspIntegrationProvider provider, Project project, Runnable startClient) {
        if (LspNodeRuntimeManager.getInstance().getRuntime() != null) {
            startClient.run();
            return;
        }
        if (project.isDefault() || project.isDisposed()) {
            return;
        }
        getInstance(project).restartWhenRuntimeReady(provider.getClass());
    }

    /**
     * Registers {@code providerClass} for a restart, and starts the download when no task runs yet.
     * <p>
     * A provider that arrives while the download runs joins it. It needs no task and no second progress
     * bar, and the restart below still reaches it.
     * <p>
     * A failed download leaves nothing behind, so the next opened file asks again. There is no retry
     * loop and no back-off, which matches the ACP manager this code comes from.
     */
    public void restartWhenRuntimeReady(Class<? extends LspIntegrationProvider> providerClass) {
        myProvidersToRestart.add(providerClass);
        if (!myDownloadRunning.compareAndSet(false, true)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("A Node.js download already runs. " + providerClass.getName() + " waits for it.");
            }
            return;
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(
            myProject,
            LspLocalize.lspRuntimeNodeProgressTitle(),
            true
        ) {
            @Override
            public void run(ProgressIndicator indicator) {
                LspNodeRuntime runtime = LspNodeRuntimeManager.getInstance().ensureRuntime(indicator);
                if (runtime == null) {
                    LOG.warn("No Node.js runtime. The LSP clients of " + myProvidersToRestart + " stay down.");
                    return;
                }
                LOG.info("Node.js v" + runtime.getVersion() + " is ready. Starting the clients of " + myProvidersToRestart + ".");
                LspClientManager clientManager = LspClientManager.getInstance(LspNodeRuntimeDownloads.this.myProject);
                // Takes each provider out of the set, so a provider that joins during this loop gets a
                // restart too.
                while (true) {
                    Iterator<Class<? extends LspIntegrationProvider>> iterator = myProvidersToRestart.iterator();
                    if (!iterator.hasNext()) {
                        break;
                    }
                    Class<? extends LspIntegrationProvider> waiting = iterator.next();
                    myProvidersToRestart.remove(waiting);
                    clientManager.startClientsIfNeeded(waiting);
                }
            }

            @Override
            public void onFinished() {
                // A provider still in the set waited for a download that failed, or that the user stopped. It
                // gets another chance from the next opened file, which finds an empty set and a free flag.
                myProvidersToRestart.clear();
                myDownloadRunning.set(false);
            }
        });
    }
}

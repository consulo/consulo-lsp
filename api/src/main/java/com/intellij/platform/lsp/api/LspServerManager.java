// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import consulo.disposer.Disposable;
import consulo.project.Project;

import java.util.Collection;

/**
 * @deprecated Use {@link LspClientManager}
 */
@Deprecated
@SuppressWarnings("deprecation")
public interface LspServerManager extends LspClientManager {
    /**
     * The project service is registered only against {@link LspClientManager}, so a direct service-container lookup such as
     * {@code project.getInstance(LspServerManager.class)} fails. This entry point keeps working; prefer {@link LspClientManager#getInstance}.
     *
     * @deprecated Use {@link LspClientManager#getInstance}
     */
    @Deprecated
    static LspServerManager getInstance(Project project) {
        return (LspServerManager) LspClientManager.getInstance(project);
    }

    /**
     * @deprecated Use {@link #getClients}
     */
    @Deprecated
    Collection<LspServer> getServersForProvider(Class<? extends LspServerSupportProvider> providerClass);

    /**
     * @deprecated Use {@link #startClientsIfNeeded}
     */
    @Deprecated
    void startServersIfNeeded(Class<? extends LspServerSupportProvider> providerClass);

    /**
     * @deprecated Use {@link #ensureClientStarted}
     */
    @Deprecated
    void ensureServerStarted(Class<? extends LspServerSupportProvider> providerClass, LspServerDescriptor descriptor);

    /**
     * @deprecated Use {@link #stopClients}
     */
    @Deprecated
    void stopServers(Class<? extends LspServerSupportProvider> providerClass);

    /**
     * @deprecated Use {@link #stopAndRestartClientsIfNeeded}
     */
    @Deprecated
    void stopAndRestartIfNeeded(Class<? extends LspServerSupportProvider> providerClass);

    /**
     * @deprecated Use {@link #addListener}
     */
    @Deprecated
    default void addLspServerManagerListener(LspServerManagerListener listener, Disposable parentDisposable) {
        addLspServerManagerListener(listener, parentDisposable, false);
    }

    /**
     * @deprecated Use {@link #addListener}
     */
    @Deprecated
    default void addLspServerManagerListener(LspServerManagerListener listener,
                                             Disposable parentDisposable,
                                             boolean sendEventsForExistingServers) {
        addListener(listener, parentDisposable, sendEventsForExistingServers);
    }
}

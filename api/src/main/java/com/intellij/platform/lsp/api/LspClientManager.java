// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.disposer.Disposable;
import consulo.project.Project;

import java.util.Collection;

/**
 * Tracks started LSP clients, allows starting, restarting, and stopping LSP clients.
 * <p>
 * Plugins that want to start LSP clients should implement {@link LspIntegrationProvider}.
 * <p>
 * See {@link LspIntegrationProvider#fileOpened} function documentation for information about starting an LSP client.
 */
@ServiceAPI(ComponentScope.PROJECT)
public interface LspClientManager {
    /**
     * Returns an {@code LspClientManager} instance for the given project. The passed {@code project} parameter must not be the 'default project'
     * (the fake one used to manage settings for new projects). So, some callers may need to check {@link Project#isDefault} before calling
     * {@code LspClientManager.getInstance(project)}.
     */
    static LspClientManager getInstance(Project project) {
        return project.getInstance(LspClientManager.class);
    }

    Collection<LspClient> getClients(Class<? extends LspIntegrationProvider> providerClass);

    /**
     * This function is designed for cases like "a user has enabled some framework support in Settings." It notifies the {@code providerClass}
     * about the files that are open in the editor by scheduling the {@link LspIntegrationProvider#fileOpened} function calls. So, if the
     * {@link LspIntegrationProvider#fileOpened fileOpened} function is implemented according to its documentation, this function guarantees
     * that all {@link LspClient LSP servers} needed for the currently open files will get started.
     * <p>
     * If one or more {@link LspClient LSP servers} are already running, and all the files that are open in the editor are within the roots
     * of the running servers, then this function doesn't do anything.
     * <p>
     * This function may be called from any thread.
     *
     * @see LspIntegrationProvider#fileOpened
     */
    void startClientsIfNeeded(Class<? extends LspIntegrationProvider> providerClass);

    /**
     * This function starts an LSP server even if no files are currently open in the editor,
     * so consider using {@link #startClientsIfNeeded} instead.
     * <p>
     * If an {@link LspClient LSP server} is already running, and it has the same roots as the passed {@code descriptor},
     * then this function doesn't do anything.
     * <p>
     * This function may be called from any thread.
     */
    void ensureClientStarted(Class<? extends LspIntegrationProvider> providerClass, LspClientDescriptor descriptor);

    /**
     * This function is designed for cases like "a user has disabled some framework support in Settings."
     * It stops all running LSP servers associated with the {@code providerClass}.
     */
    void stopClients(Class<? extends LspIntegrationProvider> providerClass);

    /**
     * Just a shorthand for {@link #stopClients} followed by {@link #startClientsIfNeeded}. Typically, a plugin calls this function when, for example, a
     * user has changed some framework-specific settings, and therefore, an LSP server needs to be restarted with some other parameters.
     */
    void stopAndRestartClientsIfNeeded(Class<? extends LspIntegrationProvider> providerClass);

    void addLsp4jServerWrapper(Lsp4jServerWrapper wrapper, Disposable parentDisposable);

    default void addListener(LspClientManagerListener listener, Disposable parentDisposable) {
        addListener(listener, parentDisposable, false);
    }

    void addListener(LspClientManagerListener listener, Disposable parentDisposable, boolean sendEventsForExistingClients);
}

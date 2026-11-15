// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import com.intellij.platform.lang.lsWidget.LanguageServiceWidgetItem;
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ExtensionAPI;
import consulo.component.extension.ExtensionPointName;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Plugins register their implementations of the {@code LspIntegrationProvider} to add LSP-based support for some programming language
 * or framework. In <a href="https://microsoft.github.io/language-server-protocol/">LSP terminology</a> the IDE is the client, which is why
 * the IDE-side {@link LspClient} is named after the client rather than the server.
 *
 * @see #fileOpened
 * @see <a href="https://microsoft.github.io/language-server-protocol/">https://microsoft.github.io/language-server-protocol/</a>
 */
@ExtensionAPI(ComponentScope.APPLICATION)
public interface LspIntegrationProvider {
    ExtensionPointName<LspIntegrationProvider> EP_NAME = ExtensionPointName.create(LspIntegrationProvider.class);

    /**
     * Instance of {@link LspClientStarter} is passed to the plugin's implementation of the {@link LspIntegrationProvider#fileOpened} function.
     * If needed, the plugin may call {@link LspClientStarter#ensureClientStarted}.
     * <p>
     * Note that calling {@link LspClientStarter#ensureClientStarted}
     * after {@link LspIntegrationProvider#fileOpened} function has exited won't have any effect.
     * So, plugins should not store references to {@link LspClientStarter} instances.
     *
     * @see LspIntegrationProvider#fileOpened
     */
    interface LspClientStarter {
        /**
         * Looks for an already running {@link LspClient} that has the same roots as the passed {@code descriptor}.
         * If such a client is found, then this function does nothing.
         * If not, then a new {@link LspClient} is created and started, using the passed {@code descriptor} to control its startup and behavior.
         * <p>
         * Note that calling {@link LspClientStarter#ensureClientStarted}
         * after {@link LspIntegrationProvider#fileOpened} function has exited won't have any effect.
         * So, plugins should not store references to {@link LspClientStarter} instances.
         * <p>
         * Tip: for a running {@link LspClient}, the {@code descriptor} object that was used to start it is available as {@link LspClient#getDescriptor()}.
         */
        void ensureClientStarted(LspClientDescriptor descriptor);
    }

    /**
     * This function is a convenient way for the {@code LspIntegrationProvider} implementation to start an LSP client lazily, only when
     * needed. {@code fileOpened()} is invoked each time when a file is opened in the editor, unless an already running
     * {@link LspClient} exists and the opened file is within the server roots.
     * <p>
     * Implementations may check the file type, plugin-specific settings, and call {@link LspClientStarter#ensureClientStarted} if needed.
     * <p>
     * Typical implementation:
     * <pre>
     *    if ("foo".equals(file.getExtension()) &amp;&amp; isFooSdkConfigured(project)) {
     *       // class FooLspClientDescriptor extends ProjectWideLspClientDescriptor
     *       clientStarter.ensureClientStarted(new FooLspClientDescriptor(project));
     *    }
     * </pre>
     * Note that calling {@link LspClientStarter#ensureClientStarted} after {@link #fileOpened} function has exited won't have any effect.
     * So, plugins should not store references to {@link LspClientStarter} instances.
     * <p>
     * Plugins may want to start an LSP client not on 'file opened in the editor' event but on some other event, for example, on enabling a
     * plugin-specific framework support in Settings. In this case, plugins can call {@link LspClientManager#startClientsIfNeeded} function.
     * <p>
     * Some plugins may want to perform a time-consuming task before starting an LSP client,
     * for example, download server binaries from the internet.
     * {@link #fileOpened} function implementation shouldn't be time-consuming, as it may freeze UI.
     * Here's an example of running a time-consuming task in a background thread,
     * and starting an LSP client later using {@link LspClientManager#startClientsIfNeeded}:
     * <pre>
     *    &#64;Override
     *    public void fileOpened(Project project, VirtualFile file, LspIntegrationProvider.LspClientStarter clientStarter) {
     *      FooService fooService = FooService.getInstance(project);
     *      if (!"foo".equals(file.getExtension()) || !fooService.isFooSupportEnabled()) return;
     *
     *      if (fooService.isLspServerDownloaded()) {
     *        clientStarter.ensureClientStarted(new FooLspClientDescriptor(project));
     *      }
     *      else if (!fooService.isDownloadAlreadyScheduled()) {
     *        fooService.scheduleDownload();
     *      }
     *    }
     *
     *    // An example of the FooService.scheduleDownload() implementation:
     *    void scheduleDownload() {
     *      if (isLspServerDownloaded || !isDownloadAlreadyScheduled.compareAndSet(false, true)) return;
     *
     *      Application.get().executeOnPooledThread(() -> {
     *        downloadLspServer(); // sets `isLspServerDownloaded` to `true` on success
     *        isDownloadAlreadyScheduled.set(false);
     *        LspClientManager.getInstance(project).startClientsIfNeeded(FooLspProvider.class);
     *      });
     *    }
     * </pre>
     * Called in a background thread.
     *
     * @param file a valid local file within the project roots
     */
    @RequiredReadAction
    void fileOpened(Project project, VirtualFile file, LspClientStarter clientStarter);

    default List<LanguageServiceWidgetItem> createWidgetItems(Project project, @Nullable VirtualFile currentFile) {
        List<LanguageServiceWidgetItem> result = new ArrayList<>();
        for (LspClient lspClient : LspClientManager.getInstance(project).getClients(getClass())) {
            LspClientWidgetItem item = createWidgetItem(lspClient, currentFile);
            if (item != null) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * Creates an LSP client-specific item in the 'Language Services' status bar widget.
     * The plugins are strongly recommended to override this function to have the correct icon
     * and the 'Open plugin page in Settings' action on click.
     * <p>
     * Typical implementation:
     * <pre>
     *    &#64;Override
     *    public LspClientWidgetItem createWidgetItem(LspClient lspClient, VirtualFile currentFile) {
     *      return new LspClientWidgetItem(lspClient, currentFile, fooIcon, FooConfigurable.class);
     *    }
     * </pre>
     */
    default @Nullable LspClientWidgetItem createWidgetItem(LspClient lspClient, @Nullable VirtualFile currentFile) {
        return new LspClientWidgetItem(lspClient, currentFile);
    }

    /**
     * Enumerates providers from both the canonical {@link #EP_NAME} and the deprecated {@link LspServerSupportProvider#EP_NAME}.
     * Since {@link LspServerSupportProvider} is an {@link LspIntegrationProvider}, callers can treat all providers uniformly.
     */
    @SuppressWarnings("deprecation")
    static List<LspIntegrationProvider> getAllExtensions() {
        List<LspIntegrationProvider> result = new ArrayList<>(EP_NAME.getExtensionList());
        result.addAll(LspServerSupportProvider.EP_NAME.getExtensionList());
        return result;
    }

    @SuppressWarnings("deprecation")
    static boolean hasAnyExtensions() {
        return EP_NAME.hasAnyExtensions() || LspServerSupportProvider.EP_NAME.hasAnyExtensions();
    }
}

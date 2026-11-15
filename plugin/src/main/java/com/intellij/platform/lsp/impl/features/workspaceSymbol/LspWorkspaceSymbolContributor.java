// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.workspaceSymbol;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.customization.LspWorkspaceSymbolSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import consulo.application.dumb.DumbAware;
import consulo.application.progress.ProgressManager;
import consulo.application.util.function.Processor;
import consulo.content.scope.SearchScope;
import consulo.ide.navigation.ChooseByNameContributorEx;
import consulo.language.psi.search.FindSymbolParameters;
import consulo.language.psi.stub.IdFilter;
import consulo.navigation.NavigationItem;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolLocation;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Consulo: upstream implements {@code ChooseByNameContributorEx2}; Consulo's {@link ChooseByNameContributorEx} already has
 * {@link #processNames(Processor, FindSymbolParameters)}. {@code isAvailableNow(Project)} is not part of the Consulo API and is
 * kept as a plain method.
 */
public abstract class LspWorkspaceSymbolContributor implements ChooseByNameContributorEx, DumbAware {

    public boolean isAvailableNow(Project project) {
        return LspClientManagerImpl.getInstanceImpl(project).hasInitializingOrRunningClients();
    }

    @Override
    public void processNames(Processor<String> processor, FindSymbolParameters parameters) {
        Project project = parameters.getProject();
        String query = parameters.getCompletePattern();
        // skipping the processor.process early helps to reduce complexity from the platform side
        if (query.isBlank() || LspClientManagerImpl.getInstanceImpl(project).getRunningClients().isEmpty()) {
            return;
        }

        processor.process(query);
    }

    @Override
    public void processNames(Processor<String> processor, SearchScope scope, @Nullable IdFilter filter) {
        // ignore as it should not be called,
        // because LspWorkspaceSymbolContributor.processNames(Processor<in String>, FindSymbolParameters) is called instead
    }

    @Override
    public void processElementsWithName(String name, Processor<NavigationItem> processor, FindSymbolParameters parameters) {
        Project project = parameters.getProject();
        SearchScope scope = parameters.getSearchScope();
        List<LspClientImpl> clients = new ArrayList<>();
        for (LspClientImpl client : LspClientManagerImpl.getInstanceImpl(project).getRunningClients()) {
            if (client.getDescriptor().getLspCustomization().getWorkspaceSymbolCustomizer() instanceof LspWorkspaceSymbolSupport
                && LspServerCapabilitiesKt.supportsGoToSymbol(client)) {
                clients.add(client);
            }
        }

        if (clients.isEmpty()) {
            return;
        }

        // Consulo: upstream runs one `async` per client inside `runBlockingCancellable`; the requests are blocking,
        // so they run one after another there as well.
        for (LspClientImpl client : clients) {
            ProgressManager.checkCanceled();
            for (NavigationItem item : getWorkspaceSymbols(client, scope, name)) {
                processor.process(item);
            }
        }
    }

    protected abstract boolean shouldAcceptSymbolKind(SymbolKind symbolKind);

    /**
     * Must be called on a background thread.
     */
    private List<NavigationItem> getWorkspaceSymbols(LspClientImpl client, SearchScope scope, String query) {
        List<WorkspaceSymbol> result = client.getRequestExecutor().getWorkspaceSymbolsCaching(query);
        if (result == null) {
            return List.of();
        }

        List<NavigationItem> items = new ArrayList<>();
        for (WorkspaceSymbol symbol : result) {
            NavigationItem item = createNavigationItemFromWorkspaceSymbol(client, scope, symbol);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private @Nullable NavigationItem createNavigationItemFromWorkspaceSymbol(
        LspClient client,
        SearchScope scope,
        WorkspaceSymbol symbol
    ) {
        String uri = getUri(symbol);
        if (uri == null) {
            return null;
        }
        VirtualFile virtualFile = client.getDescriptor().findFileByUri(uri);
        if (virtualFile == null) {
            return null;
        }
        if (!scope.contains(virtualFile) || !shouldAcceptSymbolKind(symbol.getKind())) {
            return null;
        }
        LspWorkspaceSymbolSupport customizer =
            (LspWorkspaceSymbolSupport) client.getDescriptor().getLspCustomization().getWorkspaceSymbolCustomizer();
        return customizer.createNavigationItem(client, symbol);
    }

    private static @Nullable String getUri(WorkspaceSymbol symbol) {
        if (symbol.getLocation() == null) {
            return null;
        }
        Location location = symbol.getLocation().getLeft();
        if (location != null && location.getUri() != null) {
            return location.getUri();
        }
        WorkspaceSymbolLocation workspaceSymbolLocation = symbol.getLocation().getRight();
        return workspaceSymbolLocation != null ? workspaceSymbolLocation.getUri() : null;
    }
}

// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspServer;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.fileEditor.FileEditorManager;
import consulo.localize.LocalizeValue;
import consulo.navigation.ItemPresentation;
import consulo.navigation.NavigationItem;
import consulo.navigation.OpenFileDescriptor;
import consulo.navigation.OpenFileDescriptorFactory;
import consulo.project.Project;
import consulo.ui.image.Image;
import consulo.util.io.PathUtil;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolLocation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Navigation item for LSP Workspace Symbol. Construct with server and original WorkspaceSymbol.
 */
public final class LspWorkspaceSymbolNavigationItem implements NavigationItem {
    private final LspClient myLspClient;
    private final WorkspaceSymbol myWorkspaceSymbol;

    public LspWorkspaceSymbolNavigationItem(LspClient lspClient, WorkspaceSymbol workspaceSymbol) {
        myLspClient = lspClient;
        myWorkspaceSymbol = workspaceSymbol;
    }

    /**
     * @deprecated Use the LspClient constructor
     */
    @Deprecated
    @SuppressWarnings("deprecation")
    public LspWorkspaceSymbolNavigationItem(LspServer lspServer, WorkspaceSymbol workspaceSymbol) {
        this((LspClient) lspServer, workspaceSymbol);
    }

    @Override
    public String getName() {
        return myWorkspaceSymbol.getName();
    }

    @Override
    public ItemPresentation getPresentation() {
        LspSymbolKindCustomizer symbolKindCustomizer = myLspClient.getDescriptor().getLspCustomization().getSymbolKindCustomizer();
        Image icon = symbolKindCustomizer.getIcon(myWorkspaceSymbol.getKind());
        return new ItemPresentation() {
            @Override
            public LocalizeValue getPresentableText() {
                return LocalizeValue.of(getName());
            }

            @Override
            public String getLocationString() {
                String uri = getUri();
                return PathUtil.getFileName(uri != null ? uri : "");
            }

            @Override
            public @Nullable Image getIcon() {
                return icon;
            }
        };
    }

    @Override
    @RequiredReadAction
    public void navigate(boolean requestFocus) {
        VirtualFile virtualFile = resolveVirtualFile();
        if (virtualFile == null) {
            return;
        }
        Either<Location, WorkspaceSymbolLocation> location = myWorkspaceSymbol.getLocation();
        Range range = location.getLeft() != null ? location.getLeft().getRange() : null;
        Project project = myLspClient.getProject();
        if (range != null) {
            Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
            Integer offset = document != null ? Lsp4jUtilKt.getOffsetInDocument(document, range.getStart()) : null;
            int startOffset = offset != null ? offset : 0;
            OpenFileDescriptor openDescriptor = OpenFileDescriptorFactory.getInstance(project).newBuilder(virtualFile).offset(startOffset).build();
            FileEditorManager.getInstance(project).openTextEditor(openDescriptor, requestFocus);
        }
        else {
            FileEditorManager.getInstance(project).openFile(virtualFile, requestFocus);
        }
    }

    @Override
    public boolean canNavigate() {
        return true;
    }

    @Override
    public boolean canNavigateToSource() {
        return canNavigate();
    }

    private @Nullable String getUri() {
        Either<Location, WorkspaceSymbolLocation> location = myWorkspaceSymbol.getLocation();
        if (location.getLeft() != null) {
            return location.getLeft().getUri();
        }
        return location.getRight() != null ? location.getRight().getUri() : null;
    }

    private @Nullable VirtualFile resolveVirtualFile() {
        String uri = getUri();
        return uri != null ? myLspClient.getDescriptor().findFileByUri(uri) : null;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LspWorkspaceSymbolNavigationItem that)) {
            return false;
        }
        return myLspClient.equals(that.myLspClient) && myWorkspaceSymbol.equals(that.myWorkspaceSymbol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(myLspClient, myWorkspaceSymbol);
    }

    @Override
    public String toString() {
        return "LspWorkspaceSymbolNavigationItem(lspClient=" + myLspClient + ", workspaceSymbol=" + myWorkspaceSymbol + ")";
    }
}

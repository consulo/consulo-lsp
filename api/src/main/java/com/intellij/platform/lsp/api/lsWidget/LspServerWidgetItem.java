// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.lsWidget;

import com.intellij.platform.lsp.api.LspServer;
import consulo.configurable.Configurable;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.ui.image.Image;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

/**
 * @deprecated Use {@link LspClientWidgetItem}
 */
@Deprecated
public class LspServerWidgetItem extends LspClientWidgetItem {
    public LspServerWidgetItem(LspServer lspServer, @Nullable VirtualFile currentFile) {
        this(lspServer, currentFile, PlatformIconGroup.webreferencesServer());
    }

    public LspServerWidgetItem(LspServer lspServer, @Nullable VirtualFile currentFile, Image icon) {
        this(lspServer, currentFile, icon, null);
    }

    public LspServerWidgetItem(
        LspServer lspServer,
        @Nullable VirtualFile currentFile,
        Image icon,
        @Nullable Class<? extends Configurable> settingsPageClass
    ) {
        super(lspServer, currentFile, icon, settingsPageClass);
    }

    protected LspServer getLspServer() {
        return (LspServer) lspClient;
    }
}

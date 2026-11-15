// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.lsWidget;

import com.intellij.platform.lsp.api.LspClient;
import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.application.Application;
import consulo.ui.ex.action.AnAction;
import org.jspecify.annotations.Nullable;

@ServiceAPI(ComponentScope.APPLICATION)
public abstract class LspWidgetInternalService {

    public abstract @Nullable AnAction createShowErrorOutputAction(LspClient lspClient);

    /**
     * An action that shows the server log of {@code lspClient}, its console in the Services tool window.
     */
    public abstract AnAction createShowServerLogAction(LspClient lspClient);

    public abstract void restartLspClient(LspClient lspClient);

    public abstract void stopLspClient(LspClient lspClient);


    public static LspWidgetInternalService getInstance() {
        return Application.get().getInstance(LspWidgetInternalService.class);
    }
}

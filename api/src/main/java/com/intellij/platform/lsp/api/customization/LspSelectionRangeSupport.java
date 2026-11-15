// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

import consulo.virtualFileSystem.VirtualFile;

public non-sealed class LspSelectionRangeSupport extends LspSelectionRangeCustomizer {
    /**
     * This function is called when the "Extend Selection" or "Shrink Selection" action is invoked.
     * {@code True} means that the IDE should send the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_selectionRange">textDocument/selectionRange</a>
     * request to the server and use the received information for the action.
     */
    public boolean shouldAskServerForSelectionRange(VirtualFile file) {
        return true;
    }
}

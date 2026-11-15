// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

/**
 * See LSP specification: <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_selectionRange">Selection Range</a>.
 * The corresponding feature names in the IDE are "Extend Selection" and "Shrink Selection".
 */
public abstract sealed class LspSelectionRangeCustomizer permits LspSelectionRangeSupport, LspSelectionRangeDisabled {
    LspSelectionRangeCustomizer() {
    }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

/**
 * See LSP specification: <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_onTypeFormatting">textDocument/onTypeFormatting</a>.
 */
public abstract sealed class LspOnTypeFormattingCustomizer permits LspOnTypeFormattingSupport, LspOnTypeFormattingDisabled {
    LspOnTypeFormattingCustomizer() {
    }
}

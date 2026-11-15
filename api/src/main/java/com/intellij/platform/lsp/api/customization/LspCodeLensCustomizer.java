// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

/**
 * See LSP specification: <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_codeLens">Code Lens</a>.
 * The corresponding feature name in the IDE is "Code Vision".
 */
public abstract sealed class LspCodeLensCustomizer permits LspCodeLensSupport, LspCodeLensDisabled {
    LspCodeLensCustomizer() {
    }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

/**
 * Customizes the 'Optimize Imports' feature behavior.
 * <p>
 * Implementations may override this class to:
 * <ul>
 * <li>return their specific subclass of {@link LspOptimizeImportsSupport} that fine-tunes the behavior</li>
 * <li>return {@link LspOptimizeImportsDisabled} to disable the LSP feature</li>
 * </ul>
 * When enabled, the IDE will use the
 * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_codeAction">textDocument/codeAction</a>
 * request with the {@code "source.organizeImports"} code action kind to optimize imports in the file.
 */
public abstract sealed class LspOptimizeImportsCustomizer permits LspOptimizeImportsSupport, LspOptimizeImportsDisabled {
    LspOptimizeImportsCustomizer() {
    }
}

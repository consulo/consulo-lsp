// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

/**
 * Customizes the override/implement gutter icons (line markers) for LSP-backed files.
 * <p>
 * The feature combines three standard LSP requests:
 * <ul>
 * <li><a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_documentSymbol">textDocument/documentSymbol</a>
 *   provides the marker anchors.</li>
 * <li><a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_implementation">textDocument/implementation</a>
 *   provides the "implemented/overridden by" targets for a method-like symbol.</li>
 * <li><a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_prepareTypeHierarchy">prepareTypeHierarchy</a>
 *   with {@code typeHierarchy/subtypes} provides the "subtypes" targets for a class-like symbol.</li>
 * </ul>
 * Traffic gates keep the per-symbol request count low.
 * The IDE queries an interface member always.
 * The IDE queries a class method only when {@code typeHierarchy/subtypes} confirmed that the class has subtypes.
 * The IDE does not query a top-level function or a method of another container kind.
 */
public abstract sealed class LspInheritanceMarkersCustomizer permits LspInheritanceMarkersSupport, LspInheritanceMarkersDisabled {
    LspInheritanceMarkersCustomizer() {
    }
}

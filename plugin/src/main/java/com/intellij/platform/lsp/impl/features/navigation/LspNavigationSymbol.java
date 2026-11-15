// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.navigation;

import consulo.language.psi.PsiElement;
import consulo.navigation.Navigatable;

/**
 * A place an LSP server can send the IDE to. There are three kinds, and they differ in more than where they lead:
 *
 * <table>
 * <tr><th></th><th>reported by</th><th>navigates to</th><th>underlined without a modifier</th><th>Ctrl+hover label</th></tr>
 * <tr><td>{@code LspOpenBrowserNavigatableSymbol}</td><td>{@code textDocument/documentLink}</td><td>outside the IDE</td><td>yes</td><td>the link tooltip</td></tr>
 * <tr><td>{@code LspPathDocumentLinkSymbol}</td><td>{@code textDocument/documentLink}</td><td>a directory, or a file at its start</td><td>yes</td><td>none</td></tr>
 * <tr><td>{@link LspDefinitionSymbol}</td><td>{@code textDocument/definition}, {@code textDocument/typeDefinition}</td><td>a file at {@code targetSelectionRange}</td><td>no</td><td>none</td></tr>
 * </table>
 * <p>
 * What the table can't hold:
 * <ul>
 * <li>A link's eager underline and its plain-hover tooltip are not features of these symbols at all.
 *   {@code LspHighlightingApplier} renders both from
 *   {@code LspDocumentLink}, before the reference is resolved and therefore before any symbol exists.</li>
 * <li>Only a target that leaves the IDE is labelled on Ctrl+hover. For the other two, the label worth showing is what the
 *   server says the target is, which needs {@code textDocument/hover} and a hint that may arrive after the underline --
 *   IJPL-252179. A path is a poor stand-in: outside the server roots it is a full SDK path, and inside them it largely
 *   repeats the text under the pointer.</li>
 * <li>{@link LspDefinitionSymbol} has no hover of its own either:
 *   {@code LspDocumentationTargetProvider}
 *   answers by offset, so any position in an LSP-backed file gets {@code textDocument/hover}, including one over a reference.</li>
 * <li>The two underlines are different objects: a {@code HighlightInfo} from the applier, and a {@code RangeHighlighter} that
 *   {@code CtrlMouseHandler2} adds while a modifier is held. Over a document link, both can be on screen at once.</li>
 * <li>A native {@code PsiReference} shadows all three, because {@code allReferencesAround} takes the first element with references walking
 *   from the leaf up to the file, and LSP's implicit references live on the {@code PsiFile}.</li>
 * <li>An integration that turns LSP hover off gets no {@link LspDefinitionSymbol} on Ctrl+hover, only when an action is performed.
 *   Its own language support answers Ctrl+hover instead -- see {@link LspImplicitReferenceProvider}.</li>
 * </ul>
 * <p>
 * Consulo: IntelliJ's {@code Symbol}/{@code NavigatableSymbol}/{@code DocumentationTarget} API does not exist in Consulo.
 * The symbols are PSI elements (fake, non-AST) that navigate themselves, so that the PSI-based
 * {@code GotoDeclarationHandler}/{@code TypeDeclarationProvider} extensions can return them.
 * Upstream this interface is sealed; it is open here because the document link symbols are a later-stage feature.
 */
public interface LspNavigationSymbol extends PsiElement, Navigatable {
}

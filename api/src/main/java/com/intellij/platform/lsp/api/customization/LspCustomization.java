// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

/**
 * Customizes various features of the LSP support in the IDE.
 * Each property corresponds to an LSP feature and can be overridden with custom implementation.
 * To completely disable a feature, the property can be overridden to use the corresponding {@code Disabled} implementation.
 */
public class LspCustomization {
    private final LspGoToDefinitionCustomizer myGoToDefinitionCustomizer = new LspGoToDefinitionSupport();
    private final LspGoToTypeDefinitionCustomizer myGoToTypeDefinitionCustomizer = new LspGoToTypeDefinitionSupport();
    private final LspGoToImplementationCustomizer myGoToImplementationCustomizer = new LspGoToImplementationSupport();
    private final LspHoverCustomizer myHoverCustomizer = new LspHoverSupport();
    private final LspCompletionCustomizer myCompletionCustomizer = new LspCompletionSupport();
    private final LspSemanticTokensCustomizer mySemanticTokensCustomizer = new LspSemanticTokensSupport();
    private final LspDiagnosticsCustomizer myDiagnosticsCustomizer = new LspDiagnosticsSupport();
    private final LspCodeActionsCustomizer myCodeActionsCustomizer = new LspCodeActionsSupport();
    private final LspCommandsCustomizer myCommandsCustomizer = new LspCommandsSupport();
    private final LspFormattingCustomizer myFormattingCustomizer = new LspFormattingSupport();
    private final LspOnTypeFormattingCustomizer myOnTypeFormattingCustomizer = LspOnTypeFormattingDisabled.INSTANCE;
    private final LspFindReferencesCustomizer myFindReferencesCustomizer = new LspFindReferencesSupport();
    private final LspOptimizeImportsCustomizer myOptimizeImportsCustomizer = new LspOptimizeImportsSupport();
    private final LspDocumentColorCustomizer myDocumentColorCustomizer = new LspDocumentColorSupport();
    private final LspDocumentLinkCustomizer myDocumentLinkCustomizer = new LspDocumentLinkSupport();
    private final LspFoldingRangeCustomizer myFoldingRangeCustomizer = new LspFoldingRangeSupport();
    private final LspInlayHintCustomizer myInlayHintCustomizer = new LspInlayHintSupport();
    private final LspDocumentHighlightsCustomizer myDocumentHighlightsCustomizer = new LspDocumentHighlightsSupport();
    private final LspSignatureHelpCustomizer mySignatureHelpCustomizer = new LspSignatureHelpSupport();
    private final LspDocumentSymbolCustomizer myDocumentSymbolCustomizer = new LspDocumentSymbolSupport();
    private final LspWorkspaceSymbolCustomizer myWorkspaceSymbolCustomizer = new LspWorkspaceSymbolSupport();
    private final LspCallHierarchyCustomizer myCallHierarchyCustomizer = new LspCallHierarchySupport();
    private final LspTypeHierarchyCustomizer myTypeHierarchyCustomizer = new LspTypeHierarchySupport();
    private final LspSymbolKindCustomizer mySymbolKindCustomizer = new LspSymbolKindCustomizer();
    private final LspSelectionRangeCustomizer mySelectionRangeCustomizer = new LspSelectionRangeSupport();
    private final LspCodeLensCustomizer myCodeLensCustomizer = new LspCodeLensSupport();
    private final LspInheritanceMarkersCustomizer myInheritanceMarkersCustomizer = LspInheritanceMarkersDisabled.INSTANCE;
    private final LspRenameCustomizer myRenameCustomizer = new LspRenameSupport();

    /**
     * Customizes the LSP go to definition feature behavior. By default, uses standard {@link LspGoToDefinitionSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspGoToDefinitionSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspGoToDefinitionDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, the IDE will ask the LSP server for {@code Navigate -> Declaration} target by sending the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_definition">textDocument/definition</a> request.
     */
    public LspGoToDefinitionCustomizer getGoToDefinitionCustomizer() {
        return myGoToDefinitionCustomizer;
    }

    /**
     * Customizes the LSP go to type definition feature behavior. By default, uses standard {@link LspGoToTypeDefinitionSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspGoToTypeDefinitionSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspGoToTypeDefinitionDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, the IDE will ask the LSP server for {@code Navigate -> Type Declaration} target by sending the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_typeDefinition">textDocument/typeDefinition</a> request.
     */
    public LspGoToTypeDefinitionCustomizer getGoToTypeDefinitionCustomizer() {
        return myGoToTypeDefinitionCustomizer;
    }

    /**
     * Customizes the LSP go to implementation feature behavior. By default, uses standard {@link LspGoToImplementationSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspGoToImplementationSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspGoToImplementationDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, the IDE will ask the LSP server for {@code Navigate -> Implementation(s)} targets by sending the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_implementation">textDocument/implementation</a> request.
     */
    public LspGoToImplementationCustomizer getGoToImplementationCustomizer() {
        return myGoToImplementationCustomizer;
    }

    /**
     * Customizes the LSP hover feature behavior. By default, uses standard {@link LspHoverSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspHoverSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspHoverDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, the IDE will ask the LSP server for hover information by sending the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_hover">textDocument/hover</a> request.
     */
    public LspHoverCustomizer getHoverCustomizer() {
        return myHoverCustomizer;
    }

    /**
     * Customizes the LSP completion feature behavior. By default, uses standard {@link LspCompletionSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspCompletionSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspCompletionDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, handles completion requests:
     * <ul>
     * <li><a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_completion">textDocument/completion</a></li>
     * <li><a href="https://microsoft.github.io/language-server-protocol/specification/#completionItem_resolve">completionItem/resolve</a></li>
     * </ul>
     */
    public LspCompletionCustomizer getCompletionCustomizer() {
        return myCompletionCustomizer;
    }

    /**
     * Customizes the LSP semantic tokens feature behavior. By default, uses standard {@link LspSemanticTokensSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspSemanticTokensSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspSemanticTokensDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * See LSP specification:
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_semanticTokens">Semantic Tokens</a>
     * <p>
     * Plugins may provide basic syntax highlighting using not only information from the LSP server but also other approaches,
     * such as TextMate bundles.
     * <p>
     * It's possible to control the feature per file, see {@link LspSemanticTokensSupport#shouldAskServerForSemanticTokens}
     */
    public LspSemanticTokensCustomizer getSemanticTokensCustomizer() {
        return mySemanticTokensCustomizer;
    }

    /**
     * Customizes the LSP diagnostics feature behavior. By default, uses standard {@link LspDiagnosticsSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspDiagnosticsSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspDiagnosticsDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, handles:
     * <ul>
     * <li>Diagnostics pushed by the server (<a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_publishDiagnostics">textDocument/publishDiagnostics</a>)</li>
     * <li>Diagnostics pulled by the client (<a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_pullDiagnostics">textDocument/diagnostic</a>)</li>
     * </ul>
     */
    public LspDiagnosticsCustomizer getDiagnosticsCustomizer() {
        return myDiagnosticsCustomizer;
    }

    /**
     * Customizes the LSP code actions feature behavior. By default, uses standard {@link LspCodeActionsSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspCodeActionsSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspCodeActionsDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, handles <a href="https://microsoft.github.io/language-server-protocol/specification#codeAction">CodeAction</a> objects
     * received from the LSP server.
     */
    public LspCodeActionsCustomizer getCodeActionsCustomizer() {
        return myCodeActionsCustomizer;
    }

    /**
     * Customizes the LSP commands feature behavior. By default, uses standard {@link LspCommandsSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspCommandsSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspCommandsDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, handles <a href="https://microsoft.github.io/language-server-protocol/specification#command">Command</a> objects
     * received from the LSP server.
     * <p>
     * @see LspCommandsSupport.executeCommand
     */
    public LspCommandsCustomizer getCommandsCustomizer() {
        return myCommandsCustomizer;
    }

    /**
     * Customizes the LSP formatting feature behavior. By default, uses standard {@link LspFormattingSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspFormattingSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspFormattingDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, it helps to decide whether the LSP server should be used for code formatting for each particular file.
     */
    public LspFormattingCustomizer getFormattingCustomizer() {
        return myFormattingCustomizer;
    }

    /**
     * Customizes the LSP on type formatting feature behavior. The feature is disabled by default.
     * Implementations may override this property to:
     * <ul>
     * <li>return {@link LspOnTypeFormattingSupport} to enable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, the IDE will send
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_onTypeFormatting">textDocument/onTypeFormatting</a>
     * request to the server when a trigger character is typed in the editor.
     */
    public LspOnTypeFormattingCustomizer getOnTypeFormattingCustomizer() {
        return myOnTypeFormattingCustomizer;
    }

    /**
     * Customizes the LSP find references feature behavior. By default, uses standard {@link LspFindReferencesSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspFindReferencesSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspFindReferencesDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, the {@code Find Usages} and {@code Show Usages} features will use the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_references">textDocument/references</a>
     * request to get results.
     */
    public LspFindReferencesCustomizer getFindReferencesCustomizer() {
        return myFindReferencesCustomizer;
    }

    /**
     * Customizes the "Optimize Imports" feature behavior. By default, uses standard {@link LspOptimizeImportsSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspOptimizeImportsSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspOptimizeImportsDisabled} to disable the feature</li>
     * </ul>
     * <p>
     * When enabled, the IDE will use the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_codeAction">textDocument/codeAction</a>
     * request with the {@code "source.organizeImports"} code action kind to optimize imports in the file.
     */
    public LspOptimizeImportsCustomizer getOptimizeImportsCustomizer() {
        return myOptimizeImportsCustomizer;
    }

    /**
     * Customizes the LSP document color feature behavior. By default, uses standard {@link LspDocumentColorSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspDocumentColorSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspDocumentColorDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, the IDE will use the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_documentColor">textDocument/documentColor</a>
     * request to decorate color references in the editor.
     */
    public LspDocumentColorCustomizer getDocumentColorCustomizer() {
        return myDocumentColorCustomizer;
    }

    /**
     * Customizes the LSP document link feature behavior. By default, uses standard {@link LspDocumentLinkSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspDocumentLinkSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspDocumentLinkDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, the IDE will use the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_documentLink">textDocument/documentLink</a>
     * request to highlight links in the editor.
     */
    public LspDocumentLinkCustomizer getDocumentLinkCustomizer() {
        return myDocumentLinkCustomizer;
    }

    /**
     * Customizes the LSP folding range feature behavior. By default, uses standard {@link LspFoldingRangeSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspFoldingRangeSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspFoldingRangeDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, the IDE will use the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_foldingRange">textDocument/foldingRange</a>
     * request to obtain code folding regions from the LSP server.
     */
    public LspFoldingRangeCustomizer getFoldingRangeCustomizer() {
        return myFoldingRangeCustomizer;
    }

    /**
     * Customizes the LSP inlay hints feature behavior.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspInlayHintSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspInlayHintDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, the IDE will use the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_inlayHint">textDocument/inlayHint</a>
     * request to display inline hints such as parameter names, type annotations, and other contextual information
     * directly in the editor.
     */
    public LspInlayHintCustomizer getInlayHintCustomizer() {
        return myInlayHintCustomizer;
    }

    /**
     * Customizes the LSP highlight usages feature behavior. By default, uses standard {@link LspDocumentHighlightsSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspDocumentHighlightsSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspDocumentHighlightsDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, the IDE will use the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_documentHighlight">textDocument/documentHighlight</a>
     * request to provide usage highlighting to be used in the editor.
     */
    public LspDocumentHighlightsCustomizer getDocumentHighlightsCustomizer() {
        return myDocumentHighlightsCustomizer;
    }

    /**
     * Customizes the LSP signature help feature behavior. By default, uses standard {@link LspSignatureHelpSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspSignatureHelpSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspSignatureHelpDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, the IDE will use the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_signatureHelp">textDocument/signatureHelp</a>
     * request to provide parameter hints when typing function/method calls.
     */
    public LspSignatureHelpCustomizer getSignatureHelpCustomizer() {
        return mySignatureHelpCustomizer;
    }

    /**
     * Customizes the LSP document symbol feature behavior. By default, uses standard {@link LspDocumentSymbolSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspDocumentSymbolCustomizer} that fine-tunes the behavior</li>
     * <li>return {@link LspDocumentSymbolDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, the IDE will use the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_documentSymbol">textDocument/documentSymbol</a>
     * request to get file structure and breadcrumbs from the LSP server.
     * <p>
     * @see symbolKindCustomizer
     */
    public LspDocumentSymbolCustomizer getDocumentSymbolCustomizer() {
        return myDocumentSymbolCustomizer;
    }

    /**
     * Customizes the LSP workspace symbol feature behavior. By default, uses standard {@link LspWorkspaceSymbolSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspWorkspaceSymbolSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspWorkspaceSymbolDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, the IDE will use the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#workspace_symbol">workspace/symbol</a>
     * request to provide workspace-wide symbol search functionality through the "Go To Symbol" feature.
     * <p>
     * @see symbolKindCustomizer
     */
    public LspWorkspaceSymbolCustomizer getWorkspaceSymbolCustomizer() {
        return myWorkspaceSymbolCustomizer;
    }

    /**
     * Customizes the LSP call hierarchy feature behavior. By default, uses standard {@link LspCallHierarchySupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspCallHierarchySupport} that fine-tunes the behavior</li>
     * <li>return {@link LspCallHierarchyDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, the IDE will use the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_prepareCallHierarchy">textDocument/prepareCallHierarchy</a>
     * request to provide call hierarchy information for navigating between callers and callees of methods.
     */
    public LspCallHierarchyCustomizer getCallHierarchyCustomizer() {
        return myCallHierarchyCustomizer;
    }

    /**
     * Customizes the LSP type hierarchy feature behavior. By default, uses standard {@link LspTypeHierarchySupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspTypeHierarchySupport} that fine-tunes the behavior</li>
     * <li>return {@link LspTypeHierarchyDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, the IDE will use the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_prepareTypeHierarchy">textDocument/prepareTypeHierarchy</a>
     * request to provide type hierarchy information for navigating between supertypes and subtypes.
     */
    public LspTypeHierarchyCustomizer getTypeHierarchyCustomizer() {
        return myTypeHierarchyCustomizer;
    }

    /**
     * Shared customization for {@link #getDocumentSymbolCustomizer()}, {@link #getWorkspaceSymbolCustomizer()}, {@link #getCallHierarchyCustomizer()} and {@link #getTypeHierarchyCustomizer()}.
     */
    public LspSymbolKindCustomizer getSymbolKindCustomizer() {
        return mySymbolKindCustomizer;
    }

    /**
     * Customizes the LSP selection range feature behavior. By default, uses standard {@link LspSelectionRangeSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspSelectionRangeSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspSelectionRangeDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, the IDE will use the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_selectionRange">textDocument/selectionRange</a>
     * request to provide "Extend Selection" / "Shrink Selection" features in the editor.
     */
    public LspSelectionRangeCustomizer getSelectionRangeCustomizer() {
        return mySelectionRangeCustomizer;
    }

    /**
     * Customizes the LSP code lens feature behavior. By default, uses standard {@link LspCodeLensSupport} implementation.
     * Implementations may override this property to:
     * <ul>
     * <li>return their specific subclass of {@link LspCodeLensSupport} that fine-tunes the behavior</li>
     * <li>return {@link LspCodeLensDisabled} to disable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, the IDE will use the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_codeLens">textDocument/codeLens</a>
     * request to provide code lenses (called "Code Vision" in the IDE) that display contextual information
     * and actions directly in the editor.
     */
    public LspCodeLensCustomizer getCodeLensCustomizer() {
        return myCodeLensCustomizer;
    }

    /**
     * Customizes the override/implement gutter icons (line markers). The feature is disabled by default.
     * Implementations may override this property to:
     * <ul>
     * <li>return {@link LspInheritanceMarkersSupport} or a subclass of it to enable the LSP feature</li>
     * </ul>
     * <p>
     * When enabled, the IDE finds the marker anchors with the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_documentSymbol">textDocument/documentSymbol</a>
     * request, and then computes one marker per symbol with the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_implementation">textDocument/implementation</a>
     * request for a method-like symbol, or the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_prepareTypeHierarchy">textDocument/prepareTypeHierarchy</a>
     * and {@code typeHierarchy/subtypes} requests for a class-like symbol.
     * The per-symbol requests also respect {@link #getGoToImplementationCustomizer()} and {@link #getTypeHierarchyCustomizer()}.
     * Traffic gates keep the request count low: the IDE queries an interface member always,
     * and a class method only when the class has subtypes.
     * <p>
     * The feature is opt-in because one pull costs one request per queried symbol, and server speed varies.
     */
    public LspInheritanceMarkersCustomizer getInheritanceMarkersCustomizer() {
        return myInheritanceMarkersCustomizer;
    }

    /**
     * Customizes the LSP Rename functionality.
     * <p>
     * Allows overriding or extending the default behavior for renaming symbols.
     * <p>
     * Defaults to {@link LspRenameSupport}, which provides standard rename functionality.
     * <p>
     * @see <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_rename">textDocument/rename</a>
     * @see <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_prepareRename">textDocument/prepareRename</a>
     */
    public LspRenameCustomizer getRenameCustomizer() {
        return myRenameCustomizer;
    }
}

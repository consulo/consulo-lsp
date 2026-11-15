// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import com.intellij.platform.lsp.api.customization.LspCustomization;
import com.intellij.platform.lsp.api.customization.LspSemanticTokensCustomizer;
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport;
import org.eclipse.lsp4j.CallHierarchyCapabilities;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CodeActionCapabilities;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionKindCapabilities;
import org.eclipse.lsp4j.CodeActionLiteralSupportCapabilities;
import org.eclipse.lsp4j.CodeActionResolveSupportCapabilities;
import org.eclipse.lsp4j.CodeLensCapabilities;
import org.eclipse.lsp4j.ColorProviderCapabilities;
import org.eclipse.lsp4j.CompletionCapabilities;
import org.eclipse.lsp4j.CompletionItemCapabilities;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemKindCapabilities;
import org.eclipse.lsp4j.CompletionItemResolveSupportCapabilities;
import org.eclipse.lsp4j.CompletionItemTag;
import org.eclipse.lsp4j.CompletionItemTagSupportCapabilities;
import org.eclipse.lsp4j.CompletionListCapabilities;
import org.eclipse.lsp4j.DefinitionCapabilities;
import org.eclipse.lsp4j.DiagnosticCapabilities;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.DiagnosticWorkspaceCapabilities;
import org.eclipse.lsp4j.DiagnosticsTagSupport;
import org.eclipse.lsp4j.DidChangeWatchedFilesCapabilities;
import org.eclipse.lsp4j.DocumentHighlightCapabilities;
import org.eclipse.lsp4j.DocumentLinkCapabilities;
import org.eclipse.lsp4j.DocumentSymbolCapabilities;
import org.eclipse.lsp4j.ExecuteCommandCapabilities;
import org.eclipse.lsp4j.FailureHandlingKind;
import org.eclipse.lsp4j.FileOperationsWorkspaceCapabilities;
import org.eclipse.lsp4j.FoldingRangeCapabilities;
import org.eclipse.lsp4j.FoldingRangeKind;
import org.eclipse.lsp4j.FoldingRangeKindSupportCapabilities;
import org.eclipse.lsp4j.FoldingRangeSupportCapabilities;
import org.eclipse.lsp4j.FormattingCapabilities;
import org.eclipse.lsp4j.GeneralClientCapabilities;
import org.eclipse.lsp4j.HoverCapabilities;
import org.eclipse.lsp4j.ImplementationCapabilities;
import org.eclipse.lsp4j.InlayHintCapabilities;
import org.eclipse.lsp4j.InlayHintWorkspaceCapabilities;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.OnTypeFormattingCapabilities;
import org.eclipse.lsp4j.ParameterInformationCapabilities;
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities;
import org.eclipse.lsp4j.RangeFormattingCapabilities;
import org.eclipse.lsp4j.ReferencesCapabilities;
import org.eclipse.lsp4j.RenameCapabilities;
import org.eclipse.lsp4j.ResourceOperationKind;
import org.eclipse.lsp4j.SelectionRangeCapabilities;
import org.eclipse.lsp4j.SemanticTokensCapabilities;
import org.eclipse.lsp4j.SemanticTokensClientCapabilitiesRequests;
import org.eclipse.lsp4j.SemanticTokensClientCapabilitiesRequestsFull;
import org.eclipse.lsp4j.SemanticTokensWorkspaceCapabilities;
import org.eclipse.lsp4j.ShowDocumentCapabilities;
import org.eclipse.lsp4j.SignatureHelpCapabilities;
import org.eclipse.lsp4j.StaleRequestCapabilities;
import org.eclipse.lsp4j.SymbolCapabilities;
import org.eclipse.lsp4j.SymbolKindCapabilities;
import org.eclipse.lsp4j.SymbolTag;
import org.eclipse.lsp4j.SymbolTagSupportCapabilities;
import org.eclipse.lsp4j.SynchronizationCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentContentCapabilities;
import org.eclipse.lsp4j.TokenFormat;
import org.eclipse.lsp4j.TypeDefinitionCapabilities;
import org.eclipse.lsp4j.TypeHierarchyCapabilities;
import org.eclipse.lsp4j.WindowClientCapabilities;
import org.eclipse.lsp4j.WindowShowMessageRequestCapabilities;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceEditCapabilities;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.Arrays;
import java.util.List;

public final class LspClientCapabilitiesKt {
    private LspClientCapabilitiesKt() {
    }

    public static ClientCapabilities createClientCapabilities(LspCustomization lspCustomization) {
        ClientCapabilities capabilities = new ClientCapabilities();

        WorkspaceClientCapabilities workspace = new WorkspaceClientCapabilities();
        workspace.setConfiguration(true);
        workspace.setApplyEdit(true);
        workspace.setWorkspaceFolders(true);
        //configuration = true // keep false by default because [getWorkspaceConfiguration] returns null by default
        WorkspaceEditCapabilities workspaceEdit = new WorkspaceEditCapabilities();
        workspaceEdit.setDocumentChanges(true);
        workspaceEdit.setResourceOperations(List.of(ResourceOperationKind.Create, ResourceOperationKind.Rename, ResourceOperationKind.Delete));
        workspaceEdit.setFailureHandling(FailureHandlingKind.Abort);
        workspaceEdit.setNormalizesLineEndings(true);
        workspace.setWorkspaceEdit(workspaceEdit);
        DidChangeWatchedFilesCapabilities didChangeWatchedFiles = new DidChangeWatchedFilesCapabilities(true);
        didChangeWatchedFiles.setRelativePatternSupport(true);
        workspace.setDidChangeWatchedFiles(didChangeWatchedFiles);
        workspace.setExecuteCommand(new ExecuteCommandCapabilities(false));
        // static only: the client reads `workspace.textDocumentContent.schemes` from the initialize result
        workspace.setTextDocumentContent(new TextDocumentContentCapabilities(false));
        SemanticTokensWorkspaceCapabilities workspaceSemanticTokens = new SemanticTokensWorkspaceCapabilities();
        workspaceSemanticTokens.setRefreshSupport(true);
        workspace.setSemanticTokens(workspaceSemanticTokens);
        InlayHintWorkspaceCapabilities workspaceInlayHint = new InlayHintWorkspaceCapabilities();
        workspaceInlayHint.setRefreshSupport(true);
        workspace.setInlayHint(workspaceInlayHint);
        DiagnosticWorkspaceCapabilities workspaceDiagnostics = new DiagnosticWorkspaceCapabilities();
        workspaceDiagnostics.setRefreshSupport(true);
        workspace.setDiagnostics(workspaceDiagnostics);
        SymbolCapabilities symbol = new SymbolCapabilities(true);
        SymbolKindCapabilities workspaceSymbolKind = new SymbolKindCapabilities();
        workspaceSymbolKind.setValueSet(lspCustomization.getSymbolKindCustomizer().getSupportedKinds());
        symbol.setSymbolKind(workspaceSymbolKind);
        workspace.setSymbol(symbol);
        FileOperationsWorkspaceCapabilities fileOperations = new FileOperationsWorkspaceCapabilities();
        fileOperations.setDynamicRegistration(true);
        fileOperations.setWillRename(true);
        workspace.setFileOperations(fileOperations);
        capabilities.setWorkspace(workspace);

        TextDocumentClientCapabilities textDocument = new TextDocumentClientCapabilities();
        SynchronizationCapabilities synchronization = new SynchronizationCapabilities();
        // `dynamicRegistration = false` because the IDE doesn't check dynamically registered capabilities before sending
        // `didOpen` / `didChange` / `didClose` notifications.
        // It means that the IDE relies on static capability `LspClient.initializeResult.capabilities.textDocumentSync`
        synchronization.setDynamicRegistration(false);
        synchronization.setWillSave(false);
        synchronization.setWillSaveWaitUntil(false);
        synchronization.setDidSave(true);
        textDocument.setSynchronization(synchronization);
        textDocument.setInlayHint(new InlayHintCapabilities(true));
        textDocument.setDocumentHighlight(new DocumentHighlightCapabilities(true));
        textDocument.setDiagnostic(new DiagnosticCapabilities(true));
        DefinitionCapabilities definition = new DefinitionCapabilities();
        definition.setLinkSupport(true);
        textDocument.setDefinition(definition);
        TypeDefinitionCapabilities typeDefinition = new TypeDefinitionCapabilities();
        typeDefinition.setLinkSupport(true);
        textDocument.setTypeDefinition(typeDefinition);
        ImplementationCapabilities implementation = new ImplementationCapabilities();
        implementation.setLinkSupport(true);
        textDocument.setImplementation(implementation);

        CompletionCapabilities completion = new CompletionCapabilities();
        CompletionItemCapabilities completionItem = new CompletionItemCapabilities();
        completionItem.setDocumentationFormat(List.of(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT));
        completionItem.setDeprecatedSupport(true);
        completionItem.setTagSupport(new CompletionItemTagSupportCapabilities(List.of(CompletionItemTag.Deprecated)));
        completionItem.setInsertReplaceSupport(true);
        completionItem.setLabelDetailsSupport(true);
        completionItem.setSnippetSupport(true);
        CompletionItemResolveSupportCapabilities completionItemResolveSupport = new CompletionItemResolveSupportCapabilities();
        completionItemResolveSupport.setProperties(List.of("documentation"));
        completionItem.setResolveSupport(completionItemResolveSupport);
        completion.setCompletionItem(completionItem);
        CompletionItemKindCapabilities completionItemKind = new CompletionItemKindCapabilities();
        completionItemKind.setValueSet(Arrays.asList(CompletionItemKind.values()));
        completion.setCompletionItemKind(completionItemKind);
        CompletionListCapabilities completionList = new CompletionListCapabilities();
        completionList.setItemDefaults(List.of("commitCharacters", "editRange", "insertTextFormat", "insertTextMode", "data"));
        completionList.setApplyKindSupport(true);
        completion.setCompletionList(completionList);
        textDocument.setCompletion(completion);

        HoverCapabilities hover = new HoverCapabilities();
        hover.setContentFormat(List.of(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT));
        textDocument.setHover(hover);

        SignatureHelpCapabilities signatureHelp = new SignatureHelpCapabilities(true);
        SignatureInformationCapabilitiesWithNoActiveParameterSupport signatureInformation =
            new SignatureInformationCapabilitiesWithNoActiveParameterSupport();
        signatureInformation.setDocumentationFormat(List.of(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT));
        ParameterInformationCapabilities parameterInformation = new ParameterInformationCapabilities();
        parameterInformation.setLabelOffsetSupport(true);
        signatureInformation.setParameterInformation(parameterInformation);
        signatureInformation.setActiveParameterSupport(true);
        signatureInformation.setNoActiveParameterSupport(true);
        signatureHelp.setSignatureInformation(signatureInformation);
        textDocument.setSignatureHelp(signatureHelp);

        DocumentSymbolCapabilities documentSymbol = new DocumentSymbolCapabilities();
        documentSymbol.setDynamicRegistration(true);
        SymbolKindCapabilities documentSymbolKind = new SymbolKindCapabilities();
        documentSymbolKind.setValueSet(lspCustomization.getSymbolKindCustomizer().getSupportedKinds());
        documentSymbol.setSymbolKind(documentSymbolKind);
        documentSymbol.setHierarchicalDocumentSymbolSupport(true);
        documentSymbol.setTagSupport(new SymbolTagSupportCapabilities(List.of(SymbolTag.Deprecated)));
        documentSymbol.setLabelSupport(false);
        textDocument.setDocumentSymbol(documentSymbol);

        FoldingRangeCapabilities foldingRange = new FoldingRangeCapabilities();
        foldingRange.setDynamicRegistration(true);
        foldingRange.setRangeLimit(null);
        foldingRange.setLineFoldingOnly(false);
        FoldingRangeKindSupportCapabilities foldingRangeKind = new FoldingRangeKindSupportCapabilities();
        foldingRangeKind.setValueSet(List.of(FoldingRangeKind.Imports, FoldingRangeKind.Region));
        foldingRange.setFoldingRangeKind(foldingRangeKind);
        FoldingRangeSupportCapabilities foldingRangeSupport = new FoldingRangeSupportCapabilities();
        foldingRangeSupport.setCollapsedText(true);
        foldingRange.setFoldingRange(foldingRangeSupport);
        textDocument.setFoldingRange(foldingRange);

        LspSemanticTokensCustomizer semanticTokensCustomizer = lspCustomization.getSemanticTokensCustomizer();
        if (semanticTokensCustomizer instanceof LspSemanticTokensSupport semanticTokensSupport) {
            SemanticTokensCapabilities semanticTokens = new SemanticTokensCapabilities();
            SemanticTokensClientCapabilitiesRequests requests = new SemanticTokensClientCapabilitiesRequests();
            requests.setRange(Either.forLeft(false));
            SemanticTokensClientCapabilitiesRequestsFull full = new SemanticTokensClientCapabilitiesRequestsFull();
            full.setDelta(false);
            requests.setFull(Either.forRight(full));
            semanticTokens.setRequests(requests);
            semanticTokens.setTokenTypes(semanticTokensSupport.getTokenTypes());
            semanticTokens.setTokenModifiers(semanticTokensSupport.getTokenModifiers());
            semanticTokens.setFormats(List.of(TokenFormat.Relative));
            semanticTokens.setOverlappingTokenSupport(true);
            semanticTokens.setMultilineTokenSupport(true);
            semanticTokens.setServerCancelSupport(false);
            textDocument.setSemanticTokens(semanticTokens);
        }

        PublishDiagnosticsCapabilities publishDiagnostics = new PublishDiagnosticsCapabilities();
        publishDiagnostics.setVersionSupport(true);
        publishDiagnostics.setTagSupport(Either.forRight(new DiagnosticsTagSupport(List.of(DiagnosticTag.Unnecessary, DiagnosticTag.Deprecated))));
        publishDiagnostics.setDataSupport(true);
        textDocument.setPublishDiagnostics(publishDiagnostics);

        CodeActionCapabilities codeAction = new CodeActionCapabilities();
        CodeActionLiteralSupportCapabilities codeActionLiteralSupport = new CodeActionLiteralSupportCapabilities();
        codeActionLiteralSupport.setCodeActionKind(new CodeActionKindCapabilities(
            List.of(
                CodeActionKind.QuickFix,
                CodeActionKind.Empty,
                CodeActionKind.Source,
                CodeActionKind.Refactor,
                CodeActionKind.RefactorInline,
                CodeActionKind.RefactorExtract
            )
        ));
        codeAction.setCodeActionLiteralSupport(codeActionLiteralSupport);
        codeAction.setDisabledSupport(true);
        codeAction.setDataSupport(true);
        CodeActionResolveSupportCapabilities codeActionResolveSupport = new CodeActionResolveSupportCapabilities();
        codeActionResolveSupport.setProperties(List.of("edit"));
        codeAction.setResolveSupport(codeActionResolveSupport);
        textDocument.setCodeAction(codeAction);

        textDocument.setFormatting(new FormattingCapabilities(true));
        textDocument.setRangeFormatting(new RangeFormattingCapabilities(true));
        textDocument.setOnTypeFormatting(new OnTypeFormattingCapabilities(true));
        textDocument.setReferences(new ReferencesCapabilities(true));
        textDocument.setColorProvider(new ColorProviderCapabilities(true));
        DocumentLinkCapabilities documentLink = new DocumentLinkCapabilities(true);
        documentLink.setTooltipSupport(true);
        textDocument.setDocumentLink(documentLink);
        textDocument.setCallHierarchy(new CallHierarchyCapabilities(true));
        textDocument.setTypeHierarchy(new TypeHierarchyCapabilities(true));
        textDocument.setSelectionRange(new SelectionRangeCapabilities(true));
        textDocument.setCodeLens(new CodeLensCapabilities(true));
        RenameCapabilities rename = new RenameCapabilities(true);
        rename.setPrepareSupport(true);
        textDocument.setRename(rename);
        capabilities.setTextDocument(textDocument);

        capabilities.setNotebookDocument(null);

        WindowClientCapabilities window = new WindowClientCapabilities();
        window.setShowMessage(new WindowShowMessageRequestCapabilities());
        window.setShowDocument(new ShowDocumentCapabilities(true));
        window.setWorkDoneProgress(true);
        capabilities.setWindow(window);

        GeneralClientCapabilities general = new GeneralClientCapabilities();
        StaleRequestCapabilities staleRequestSupport = new StaleRequestCapabilities();
        staleRequestSupport.setCancel(true);
        general.setStaleRequestSupport(staleRequestSupport);
        capabilities.setGeneral(general);

        capabilities.setExperimental(null);
        return capabilities;
    }
}

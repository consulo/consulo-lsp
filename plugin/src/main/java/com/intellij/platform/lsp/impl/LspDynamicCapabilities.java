package com.intellij.platform.lsp.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import consulo.logging.Logger;
import consulo.util.collection.MultiMap;
import consulo.util.lang.Pair;
import org.eclipse.lsp4j.CallHierarchyRegistrationOptions;
import org.eclipse.lsp4j.CodeActionRegistrationOptions;
import org.eclipse.lsp4j.CodeLensRegistrationOptions;
import org.eclipse.lsp4j.ColorProviderOptions;
import org.eclipse.lsp4j.CompletionRegistrationOptions;
import org.eclipse.lsp4j.DeclarationRegistrationOptions;
import org.eclipse.lsp4j.DefinitionRegistrationOptions;
import org.eclipse.lsp4j.DiagnosticRegistrationOptions;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.DocumentFormattingRegistrationOptions;
import org.eclipse.lsp4j.DocumentHighlightRegistrationOptions;
import org.eclipse.lsp4j.DocumentLinkRegistrationOptions;
import org.eclipse.lsp4j.DocumentOnTypeFormattingRegistrationOptions;
import org.eclipse.lsp4j.DocumentRangeFormattingRegistrationOptions;
import org.eclipse.lsp4j.DocumentSymbolRegistrationOptions;
import org.eclipse.lsp4j.ExecuteCommandRegistrationOptions;
import org.eclipse.lsp4j.FileOperationOptions;
import org.eclipse.lsp4j.FoldingRangeProviderOptions;
import org.eclipse.lsp4j.HoverRegistrationOptions;
import org.eclipse.lsp4j.ImplementationRegistrationOptions;
import org.eclipse.lsp4j.InlayHintRegistrationOptions;
import org.eclipse.lsp4j.InlineValueRegistrationOptions;
import org.eclipse.lsp4j.LinkedEditingRangeRegistrationOptions;
import org.eclipse.lsp4j.MonikerRegistrationOptions;
import org.eclipse.lsp4j.ReferenceRegistrationOptions;
import org.eclipse.lsp4j.RenameOptions;
import org.eclipse.lsp4j.SelectionRangeRegistrationOptions;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.SignatureHelpRegistrationOptions;
import org.eclipse.lsp4j.TextDocumentChangeRegistrationOptions;
import org.eclipse.lsp4j.TextDocumentRegistrationOptions;
import org.eclipse.lsp4j.TextDocumentSaveRegistrationOptions;
import org.eclipse.lsp4j.TypeDefinitionRegistrationOptions;
import org.eclipse.lsp4j.TypeHierarchyRegistrationOptions;
import org.eclipse.lsp4j.WorkspaceSymbolRegistrationOptions;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.Unregistration;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tracks dynamically registered IDE capabilities, which means handling the
 * <a href="https://microsoft.github.io/language-server-protocol/specification/#client_registerCapability">client/registerCapability</a> and
 * <a href="https://microsoft.github.io/language-server-protocol/specification/#client_unregisterCapability">client/unregisterCapability</a>
 * requests from the server.
 */
public class LspDynamicCapabilities {
    private static final Logger LOG = Logger.getInstance(LspDynamicCapabilities.class);

    private record CapabilityInfo(
        String registrationId,
        @Nullable JsonObject rawRegistrationOptions,
        @Nullable Object lsp4jRegistrationOptions
    ) {
    }

    private static final Map<String, Class<?>> ourCapabilityToLsp4jRegistrationOptionsClass = new HashMap<>();

    // all `org.eclipse.lsp4j.*RegistrationOption` classes plus some classes without matching suffix
    // except unrelated NotebookDocumentSyncRegistrationOptions, StaticRegistrationOptions
    private static final Pair<String, Class<CallHierarchyRegistrationOptions>> PREPARE_CALL_HIERARCHY = registration("textDocument/prepareCallHierarchy", CallHierarchyRegistrationOptions.class);
    private static final Pair<String, Class<CodeActionRegistrationOptions>> CODE_ACTION = registration("textDocument/codeAction", CodeActionRegistrationOptions.class);
    private static final Pair<String, Class<CodeLensRegistrationOptions>> CODE_LENS = registration("textDocument/codeLens", CodeLensRegistrationOptions.class);
    private static final Pair<String, Class<ColorProviderOptions>> DOCUMENT_COLOR = registration("textDocument/documentColor", ColorProviderOptions.class);
    private static final Pair<String, Class<CompletionRegistrationOptions>> COMPLETION = registration("textDocument/completion", CompletionRegistrationOptions.class);
    private static final Pair<String, Class<DeclarationRegistrationOptions>> DECLARATION = registration("textDocument/declaration", DeclarationRegistrationOptions.class);
    private static final Pair<String, Class<DefinitionRegistrationOptions>> DEFINITION = registration("textDocument/definition", DefinitionRegistrationOptions.class);
    private static final Pair<String, Class<DiagnosticRegistrationOptions>> DIAGNOSTIC = registration("textDocument/diagnostic", DiagnosticRegistrationOptions.class);
    private static final Pair<String, Class<DidChangeWatchedFilesRegistrationOptions>> DID_CHANGE_WATCHED_FILES = registration("workspace/didChangeWatchedFiles", DidChangeWatchedFilesRegistrationOptions.class);
    private static final Pair<String, Class<DocumentFormattingRegistrationOptions>> FORMATTING = registration("textDocument/formatting", DocumentFormattingRegistrationOptions.class);
    private static final Pair<String, Class<DocumentHighlightRegistrationOptions>> DOCUMENT_HIGHLIGHT = registration("textDocument/documentHighlight", DocumentHighlightRegistrationOptions.class);
    private static final Pair<String, Class<DocumentLinkRegistrationOptions>> DOCUMENT_LINK = registration("textDocument/documentLink", DocumentLinkRegistrationOptions.class);
    private static final Pair<String, Class<DocumentOnTypeFormattingRegistrationOptions>> ON_TYPE_FORMATTING = registration("textDocument/onTypeFormatting", DocumentOnTypeFormattingRegistrationOptions.class);
    private static final Pair<String, Class<DocumentRangeFormattingRegistrationOptions>> RANGE_FORMATTING = registration("textDocument/rangeFormatting", DocumentRangeFormattingRegistrationOptions.class);
    private static final Pair<String, Class<DocumentSymbolRegistrationOptions>> DOCUMENT_SYMBOL = registration("textDocument/documentSymbol", DocumentSymbolRegistrationOptions.class);
    private static final Pair<String, Class<FoldingRangeProviderOptions>> FOLDING_RANGE = registration("textDocument/foldingRange", FoldingRangeProviderOptions.class);
    private static final Pair<String, Class<ExecuteCommandRegistrationOptions>> EXECUTE_COMMAND = registration("workspace/executeCommand", ExecuteCommandRegistrationOptions.class);
    private static final Pair<String, Class<HoverRegistrationOptions>> HOVER = registration("textDocument/hover", HoverRegistrationOptions.class);
    private static final Pair<String, Class<ImplementationRegistrationOptions>> IMPLEMENTATION = registration("textDocument/implementation", ImplementationRegistrationOptions.class);
    private static final Pair<String, Class<InlayHintRegistrationOptions>> INLAY_HINT = registration("textDocument/inlayHint", InlayHintRegistrationOptions.class);
    private static final Pair<String, Class<InlineValueRegistrationOptions>> INLINE_VALUE = registration("textDocument/inlineValue", InlineValueRegistrationOptions.class);
    private static final Pair<String, Class<LinkedEditingRangeRegistrationOptions>> LINKED_EDITING_RANGE = registration("textDocument/linkedEditingRange", LinkedEditingRangeRegistrationOptions.class);
    private static final Pair<String, Class<MonikerRegistrationOptions>> MONIKER = registration("textDocument/moniker", MonikerRegistrationOptions.class);
    private static final Pair<String, Class<ReferenceRegistrationOptions>> REFERENCES = registration("textDocument/references", ReferenceRegistrationOptions.class);
    private static final Pair<String, Class<RenameOptions>> RENAME = registration("textDocument/rename", RenameOptions.class);
    private static final Pair<String, Class<SelectionRangeRegistrationOptions>> SELECTION_RANGE = registration("textDocument/selectionRange", SelectionRangeRegistrationOptions.class);
    private static final Pair<String, Class<SemanticTokensWithRegistrationOptions>> SEMANTIC_TOKENS = registration("textDocument/semanticTokens", SemanticTokensWithRegistrationOptions.class);
    private static final Pair<String, Class<SignatureHelpRegistrationOptions>> SIGNATURE_HELP = registration("textDocument/signatureHelp", SignatureHelpRegistrationOptions.class);
    private static final Pair<String, Class<TextDocumentRegistrationOptions>> DID_OPEN = registration("textDocument/didOpen", TextDocumentRegistrationOptions.class);
    private static final Pair<String, Class<TextDocumentChangeRegistrationOptions>> DID_CHANGE = registration("textDocument/didChange", TextDocumentChangeRegistrationOptions.class);
    private static final Pair<String, Class<TextDocumentRegistrationOptions>> WILL_SAVE = registration("textDocument/willSave", TextDocumentRegistrationOptions.class);
    private static final Pair<String, Class<TextDocumentRegistrationOptions>> WILL_SAVE_WAIT_UNTIL = registration("textDocument/willSaveWaitUntil", TextDocumentRegistrationOptions.class);
    private static final Pair<String, Class<TextDocumentRegistrationOptions>> DID_CLOSE = registration("textDocument/didClose", TextDocumentRegistrationOptions.class);
    private static final Pair<String, Class<TextDocumentSaveRegistrationOptions>> DID_SAVE = registration("textDocument/didSave", TextDocumentSaveRegistrationOptions.class);
    private static final Pair<String, Class<TypeDefinitionRegistrationOptions>> TYPE_DEFINITION = registration("textDocument/typeDefinition", TypeDefinitionRegistrationOptions.class);
    private static final Pair<String, Class<TypeHierarchyRegistrationOptions>> PREPARE_TYPE_HIERARCHY = registration("textDocument/prepareTypeHierarchy", TypeHierarchyRegistrationOptions.class);
    private static final Pair<String, Class<WorkspaceSymbolRegistrationOptions>> SYMBOL = registration("workspace/symbol", WorkspaceSymbolRegistrationOptions.class);
    private static final Pair<String, Class<FileOperationOptions>> WILL_RENAME_FILES = registration("workspace/willRenameFiles", FileOperationOptions.class);

    private static <T> Pair<String, Class<T>> registration(String registrationCapability, Class<T> registrationOptionsClass) {
        Pair<String, Class<T>> result = Pair.create(registrationCapability, registrationOptionsClass);
        ourCapabilityToLsp4jRegistrationOptionsClass.put(registrationCapability, registrationOptionsClass);
        return result;
    }

    public static Pair<String, Class<CallHierarchyRegistrationOptions>> getPrepareCallHierarchy() {
        return PREPARE_CALL_HIERARCHY;
    }

    public static Pair<String, Class<CodeActionRegistrationOptions>> getCodeAction() {
        return CODE_ACTION;
    }

    public static Pair<String, Class<CodeLensRegistrationOptions>> getCodeLens() {
        return CODE_LENS;
    }

    public static Pair<String, Class<ColorProviderOptions>> getDocumentColor() {
        return DOCUMENT_COLOR;
    }

    public static Pair<String, Class<CompletionRegistrationOptions>> getCompletion() {
        return COMPLETION;
    }

    public static Pair<String, Class<DeclarationRegistrationOptions>> getDeclaration() {
        return DECLARATION;
    }

    public static Pair<String, Class<DefinitionRegistrationOptions>> getDefinition() {
        return DEFINITION;
    }

    public static Pair<String, Class<DiagnosticRegistrationOptions>> getDiagnostic() {
        return DIAGNOSTIC;
    }

    public static Pair<String, Class<DidChangeWatchedFilesRegistrationOptions>> getDidChangeWatchedFiles() {
        return DID_CHANGE_WATCHED_FILES;
    }

    public static Pair<String, Class<DocumentFormattingRegistrationOptions>> getFormatting() {
        return FORMATTING;
    }

    public static Pair<String, Class<DocumentHighlightRegistrationOptions>> getDocumentHighlight() {
        return DOCUMENT_HIGHLIGHT;
    }

    public static Pair<String, Class<DocumentLinkRegistrationOptions>> getDocumentLink() {
        return DOCUMENT_LINK;
    }

    public static Pair<String, Class<DocumentOnTypeFormattingRegistrationOptions>> getOnTypeFormatting() {
        return ON_TYPE_FORMATTING;
    }

    public static Pair<String, Class<DocumentRangeFormattingRegistrationOptions>> getRangeFormatting() {
        return RANGE_FORMATTING;
    }

    public static Pair<String, Class<DocumentSymbolRegistrationOptions>> getDocumentSymbol() {
        return DOCUMENT_SYMBOL;
    }

    public static Pair<String, Class<FoldingRangeProviderOptions>> getFoldingRange() {
        return FOLDING_RANGE;
    }

    public static Pair<String, Class<ExecuteCommandRegistrationOptions>> getExecuteCommand() {
        return EXECUTE_COMMAND;
    }

    public static Pair<String, Class<HoverRegistrationOptions>> getHover() {
        return HOVER;
    }

    public static Pair<String, Class<ImplementationRegistrationOptions>> getImplementation() {
        return IMPLEMENTATION;
    }

    public static Pair<String, Class<InlayHintRegistrationOptions>> getInlayHint() {
        return INLAY_HINT;
    }

    public static Pair<String, Class<InlineValueRegistrationOptions>> getInlineValue() {
        return INLINE_VALUE;
    }

    public static Pair<String, Class<LinkedEditingRangeRegistrationOptions>> getLinkedEditingRange() {
        return LINKED_EDITING_RANGE;
    }

    public static Pair<String, Class<MonikerRegistrationOptions>> getMoniker() {
        return MONIKER;
    }

    public static Pair<String, Class<ReferenceRegistrationOptions>> getReferences() {
        return REFERENCES;
    }

    public static Pair<String, Class<RenameOptions>> getRename() {
        return RENAME;
    }

    public static Pair<String, Class<SelectionRangeRegistrationOptions>> getSelectionRange() {
        return SELECTION_RANGE;
    }

    public static Pair<String, Class<SemanticTokensWithRegistrationOptions>> getSemanticTokens() {
        return SEMANTIC_TOKENS;
    }

    public static Pair<String, Class<SignatureHelpRegistrationOptions>> getSignatureHelp() {
        return SIGNATURE_HELP;
    }

    public static Pair<String, Class<TextDocumentRegistrationOptions>> getDidOpen() {
        return DID_OPEN;
    }

    public static Pair<String, Class<TextDocumentChangeRegistrationOptions>> getDidChange() {
        return DID_CHANGE;
    }

    public static Pair<String, Class<TextDocumentRegistrationOptions>> getWillSave() {
        return WILL_SAVE;
    }

    public static Pair<String, Class<TextDocumentRegistrationOptions>> getWillSaveWaitUntil() {
        return WILL_SAVE_WAIT_UNTIL;
    }

    public static Pair<String, Class<TextDocumentRegistrationOptions>> getDidClose() {
        return DID_CLOSE;
    }

    public static Pair<String, Class<TextDocumentSaveRegistrationOptions>> getDidSave() {
        return DID_SAVE;
    }

    public static Pair<String, Class<TypeDefinitionRegistrationOptions>> getTypeDefinition() {
        return TYPE_DEFINITION;
    }

    public static Pair<String, Class<TypeHierarchyRegistrationOptions>> getPrepareTypeHierarchy() {
        return PREPARE_TYPE_HIERARCHY;
    }

    public static Pair<String, Class<WorkspaceSymbolRegistrationOptions>> getSymbol() {
        return SYMBOL;
    }

    public static Pair<String, Class<FileOperationOptions>> getWillRenameFiles() {
        return WILL_RENAME_FILES;
    }

    private final MultiMap<String, CapabilityInfo> myCapabilityToInfo = MultiMap.createConcurrent();
    private final Gson myGson = new MessageJsonHandler(Map.of()).getGson();

    /**
     * Handles the <a href="https://microsoft.github.io/language-server-protocol/specification/#client_registerCapability">client/registerCapability</a>
     * requests from the server.
     */
    public void registerCapability(Registration registration) {
        myCapabilityToInfo.putValue(registration.getMethod(), new CapabilityInfo(
            registration.getId(),
            registration.getRegisterOptions() instanceof JsonObject jsonObject ? jsonObject : null,
            getLsp4jRegistrationOptionsObject(registration)
        ));
    }

    /**
     * Handles the <a href="https://microsoft.github.io/language-server-protocol/specification/#client_unregisterCapability">client/unregisterCapability</a>
     * requests from the server.
     */
    public void unregisterCapability(Unregistration unregistration) {
        Collection<CapabilityInfo> registrations = myCapabilityToInfo.get(unregistration.getMethod());
        registrations.removeIf(it -> Objects.equals(it.registrationId(), unregistration.getId()));
        if (registrations.isEmpty()) {
            myCapabilityToInfo.remove(unregistration.getMethod());
        }
    }

    private @Nullable Object getLsp4jRegistrationOptionsObject(Registration registration) {
        if (!(registration.getRegisterOptions() instanceof JsonObject jsonObject)) {
            return null;
        }
        Class<?> clazz = ourCapabilityToLsp4jRegistrationOptionsClass.get(registration.getMethod());
        if (clazz == null) {
            return null;
        }
        try {
            return myGson.fromJson(jsonObject, clazz);
        }
        catch (JsonSyntaxException e) {
            LOG.warn("Failed to convert options of `" + registration.getMethod() + "` to " + clazz.getSimpleName() + " class: " + e + "\n" + jsonObject);
            return null;
        }
    }

    /**
     * Tells whether the LSP server wants the IDE to use the {@code capability}.
     * <p>
     * Technically, it means that the {@code capability} has been passed from the server to the IDE as the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#registration">Registration.method</a> field during the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#client_registerCapability">client/registerCapability</a> request.
     * <p>
     * {@code capability} examples: {@code workspace/didChangeWatchedFiles}, {@code textDocument/codeAction}, etc.
     */
    public boolean hasCapability(String capability) {
        return myCapabilityToInfo.containsKey(capability);
    }

    /**
     * @see #hasCapability(String)
     */
    public boolean hasCapability(Pair<String, ? extends Class<?>> capabilityAndOptionsClass) {
        return hasCapability(capabilityAndOptionsClass.getFirst());
    }

    /**
     * Returns {@code org.eclipse.lsp4j.*RegistrationOption} objects associated with the capability.
     * <p>
     * Each <a href="https://microsoft.github.io/language-server-protocol/specification/#client_registerCapability">client/registerCapability</a>
     * request sent from the server to the IDE is accompanied by the corresponding
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#registration">Registration.registerOptions</a> field,
     * which is either {@code null}, or a specific {@code *RegistrationOption} class.
     * <p>
     * Example: {@code workspace/didChangeWatchedFiles} capability options
     * are presented as a {@link DidChangeWatchedFilesRegistrationOptions} class instance.
     */
    public <T> List<T> getCapabilityRegistrationOptions(Pair<String, Class<T>> capabilityAndOptionsClass) {
        List<T> result = new ArrayList<>();
        Class<T> optionsClass = capabilityAndOptionsClass.getSecond();
        for (CapabilityInfo info : myCapabilityToInfo.get(capabilityAndOptionsClass.getFirst())) {
            Object options = info.lsp4jRegistrationOptions();
            if (optionsClass.isInstance(options)) {
                result.add(optionsClass.cast(options));
            }
        }
        return result;
    }
}

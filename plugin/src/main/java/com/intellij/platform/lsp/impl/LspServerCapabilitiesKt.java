package com.intellij.platform.lsp.impl;

import com.intellij.platform.lsp.api.LspClient;
import consulo.util.lang.Pair;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.CodeActionRegistrationOptions;
import org.eclipse.lsp4j.DocumentFilter;
import org.eclipse.lsp4j.DocumentOnTypeFormattingOptions;
import org.eclipse.lsp4j.DocumentOnTypeFormattingRegistrationOptions;
import org.eclipse.lsp4j.RelativePattern;
import org.eclipse.lsp4j.RenameOptions;
import org.eclipse.lsp4j.SaveOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SignatureHelpRegistrationOptions;
import org.eclipse.lsp4j.TextDocumentContentRegistrationOptions;
import org.eclipse.lsp4j.TextDocumentRegistrationOptions;
import org.eclipse.lsp4j.TextDocumentSaveRegistrationOptions;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.WorkspaceServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public final class LspServerCapabilitiesKt {
    private LspServerCapabilitiesKt() {
    }

    /**
     * Kotlin {@code either.let { it.left ?: true }}
     */
    private static boolean leftOrTrue(Either<Boolean, ?> either) {
        Boolean left = either.getLeft();
        return left == null || left;
    }

    public static @Nullable TextDocumentSyncKind getTextDocumentSyncKind(LspClientImpl client) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        Either<TextDocumentSyncKind, TextDocumentSyncOptions> textDocumentSync = serverCapabilities != null ? serverCapabilities.getTextDocumentSync() : null;
        if (textDocumentSync == null) {
            return null;
        }
        return textDocumentSync.map(it -> it, TextDocumentSyncOptions::getChange);
    }

    public static boolean isNotebookSupportedByServer(LspClient client) {
        ServerCapabilities serverCapabilities = ((LspClientImpl) client).getServerCapabilities();
        return serverCapabilities != null && serverCapabilities.getNotebookDocumentSync() != null;
    }

    public static boolean supportsPullDiagnostics(LspClientImpl client, VirtualFile file) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        return serverCapabilities != null && serverCapabilities.getDiagnosticProvider() != null ||
            hasDynamicCapabilityToHandleThisFile(client, file, LspDynamicCapabilities.getDiagnostic());
    }

    public static boolean supportsDocumentColor(LspClientImpl client, VirtualFile file) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        if (serverCapabilities != null && serverCapabilities.getColorProvider() != null) {
            return leftOrTrue(serverCapabilities.getColorProvider());
        }
        return hasDynamicCapabilityToHandleThisFile(client, file, LspDynamicCapabilities.getDocumentColor());
    }

    public static boolean supportsDocumentLink(LspClientImpl client, VirtualFile file) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        return serverCapabilities != null && serverCapabilities.getDocumentLinkProvider() != null ||
            hasDynamicCapabilityToHandleThisFile(client, file, LspDynamicCapabilities.getDocumentLink());
    }

    public static boolean supportsDocumentSymbol(LspClientImpl client, VirtualFile file) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        return serverCapabilities != null && serverCapabilities.getDocumentSymbolProvider() != null ||
            hasDynamicCapabilityToHandleThisFile(client, file, LspDynamicCapabilities.getDocumentSymbol());
    }

    public static boolean supportsFoldingRange(LspClientImpl client, VirtualFile file) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        if (serverCapabilities != null && serverCapabilities.getFoldingRangeProvider() != null) {
            return leftOrTrue(serverCapabilities.getFoldingRangeProvider());
        }
        return hasDynamicCapabilityToHandleThisFile(client, file, LspDynamicCapabilities.getFoldingRange());
    }

    public static boolean supportsCodeActions(LspClientImpl client, Predicate<List<String>> codeActionKindsFilter) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        Either<Boolean, CodeActionOptions> provider = serverCapabilities != null ? serverCapabilities.getCodeActionProvider() : null;
        if (provider == null) {
            return false;
        }
        if (provider.isLeft()) {
            return provider.getLeft();
        }
        List<String> kinds = provider.getRight().getCodeActionKinds();
        if (kinds == null) {
            return true; // well, the server doesn't have to list code action kinds explicitly, but it does support them!
        }
        return codeActionKindsFilter.test(kinds);
    }

    public static boolean supportsGotoDefinition(LspClientImpl client) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        return serverCapabilities != null && serverCapabilities.getDefinitionProvider() != null && leftOrTrue(serverCapabilities.getDefinitionProvider());
    }

    public static boolean supportsGotoTypeDefinition(LspClientImpl client) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        return serverCapabilities != null && serverCapabilities.getTypeDefinitionProvider() != null &&
            leftOrTrue(serverCapabilities.getTypeDefinitionProvider());
    }

    public static boolean supportsGotoImplementation(LspClientImpl client, VirtualFile file) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        if (serverCapabilities != null && serverCapabilities.getImplementationProvider() != null) {
            return leftOrTrue(serverCapabilities.getImplementationProvider());
        }
        return hasDynamicCapabilityToHandleThisFile(client, file, LspDynamicCapabilities.getImplementation());
    }

    public static boolean supportsHover(LspClientImpl client) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        return serverCapabilities != null && serverCapabilities.getHoverProvider() != null && leftOrTrue(serverCapabilities.getHoverProvider());
    }

    /**
     * True when the server provides the text for {@code scheme} URIs, through the {@code workspace/textDocumentContent} request.
     */
    public static boolean providesTextDocumentContent(LspClientImpl client, @Nullable String scheme) {
        if (scheme == null) {
            return false;
        }
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        WorkspaceServerCapabilities workspace = serverCapabilities != null ? serverCapabilities.getWorkspace() : null;
        TextDocumentContentRegistrationOptions textDocumentContent = workspace != null ? workspace.getTextDocumentContent() : null;
        List<String> schemes = textDocumentContent != null ? textDocumentContent.getSchemes() : null;
        return schemes != null && schemes.contains(scheme);
    }

    public static boolean supportsFindReferences(LspClientImpl client, VirtualFile file) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        if (serverCapabilities != null && serverCapabilities.getReferencesProvider() != null) {
            return leftOrTrue(serverCapabilities.getReferencesProvider());
        }
        return hasDynamicCapabilityToHandleThisFile(client, file, LspDynamicCapabilities.getReferences());
    }

    public static boolean supportsInlayHints(LspClientImpl client, VirtualFile file) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        if (serverCapabilities != null && serverCapabilities.getInlayHintProvider() != null) {
            return leftOrTrue(serverCapabilities.getInlayHintProvider());
        }
        return hasDynamicCapabilityToHandleThisFile(client, file, LspDynamicCapabilities.getInlayHint());
    }

    public static boolean supportsDocumentHighlights(LspClientImpl client, VirtualFile file) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        if (serverCapabilities != null && serverCapabilities.getDocumentHighlightProvider() != null) {
            return leftOrTrue(serverCapabilities.getDocumentHighlightProvider());
        }
        return hasDynamicCapabilityToHandleThisFile(client, file, LspDynamicCapabilities.getDocumentHighlight());
    }

    public static boolean supportsGoToSymbol(LspClientImpl client) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        return serverCapabilities != null && serverCapabilities.getWorkspaceSymbolProvider() != null &&
            leftOrTrue(serverCapabilities.getWorkspaceSymbolProvider())
            || client.getDynamicCapabilities().hasCapability(LspDynamicCapabilities.getSymbol());
    }

    public static boolean supportsSignatureHelp(LspClientImpl client, VirtualFile file) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        return serverCapabilities != null && serverCapabilities.getSignatureHelpProvider() != null ||
            hasDynamicCapabilityToHandleThisFile(client, file, LspDynamicCapabilities.getSignatureHelp());
    }

    public static boolean supportsCallHierarchy(LspClientImpl client, VirtualFile file) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        if (serverCapabilities != null && serverCapabilities.getCallHierarchyProvider() != null) {
            return leftOrTrue(serverCapabilities.getCallHierarchyProvider());
        }
        return hasDynamicCapabilityToHandleThisFile(client, file, LspDynamicCapabilities.getPrepareCallHierarchy());
    }

    public static boolean supportsTypeHierarchy(LspClientImpl client, VirtualFile file) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        if (serverCapabilities != null && serverCapabilities.getTypeHierarchyProvider() != null) {
            return leftOrTrue(serverCapabilities.getTypeHierarchyProvider());
        }
        return hasDynamicCapabilityToHandleThisFile(client, file, LspDynamicCapabilities.getPrepareTypeHierarchy());
    }

    public static boolean supportsSelectionRange(LspClientImpl client, VirtualFile file) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        if (serverCapabilities != null && serverCapabilities.getSelectionRangeProvider() != null) {
            return leftOrTrue(serverCapabilities.getSelectionRangeProvider());
        }
        return hasDynamicCapabilityToHandleThisFile(client, file, LspDynamicCapabilities.getSelectionRange());
    }

    public static boolean supportsCodeLens(LspClientImpl client, VirtualFile file) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        return serverCapabilities != null && serverCapabilities.getCodeLensProvider() != null ||
            hasDynamicCapabilityToHandleThisFile(client, file, LspDynamicCapabilities.getCodeLens());
    }

    public static boolean supportsRename(LspClientImpl client, VirtualFile file) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        if (serverCapabilities != null && serverCapabilities.getRenameProvider() != null) {
            return leftOrTrue(serverCapabilities.getRenameProvider());
        }
        return hasDynamicCapabilityToHandleThisFile(client, file, LspDynamicCapabilities.getRename());
    }

    public static boolean supportsPrepareRename(LspClientImpl client, VirtualFile file) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        Either<Boolean, RenameOptions> renameProvider = serverCapabilities != null ? serverCapabilities.getRenameProvider() : null;
        RenameOptions staticOptions = renameProvider != null ? renameProvider.getRight() : null;
        if (staticOptions != null && Boolean.TRUE.equals(staticOptions.getPrepareProvider())) {
            return true;
        }
        RenameOptions dynamicOptions = getDynamicCapabilityOptionsForFile(client, file, LspDynamicCapabilities.getRename());
        return dynamicOptions != null && Boolean.TRUE.equals(dynamicOptions.getPrepareProvider());
    }

    /**
     * A server does not have to list its code action kinds; no listed kinds means every kind.
     * The static capabilities do not veto a dynamic registration: the kind is supported when either source covers it.
     * Every dynamic registration matching the file counts, because a server may split its kinds across registrations.
     */
    public static boolean supportsCodeActionsOfKind(LspClientImpl client, VirtualFile file, String kind) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        Either<Boolean, CodeActionOptions> provider = serverCapabilities != null ? serverCapabilities.getCodeActionProvider() : null;
        boolean staticSupport;
        if (provider == null) {
            staticSupport = false;
        }
        else if (provider.isLeft()) {
            staticSupport = Boolean.TRUE.equals(provider.getLeft());
        }
        else {
            List<String> kinds = provider.getRight() != null ? provider.getRight().getCodeActionKinds() : null;
            staticSupport = kinds == null || matchesCodeActionKind(kinds, kind);
        }
        if (staticSupport) {
            return true;
        }
        for (CodeActionRegistrationOptions options : getDynamicCapabilityOptionsListForFile(client, file, LspDynamicCapabilities.getCodeAction())) {
            List<String> kinds = options.getCodeActionKinds();
            if (kinds == null || matchesCodeActionKind(kinds, kind)) {
                return true;
            }
        }
        return false;
    }

    // a kind covers its own dot-separated subtree only: `refactor.extract.variable2` is not under `refactor.extract.variable`
    private static boolean matchesCodeActionKind(List<String> kinds, String kind) {
        for (String it : kinds) {
            if (it.equals(kind) || kind.startsWith(it + ".") || it.startsWith(kind + ".")) {
                return true;
            }
        }
        return false;
    }

    public static @Nullable SaveOptions getDidSaveOptions(LspClientImpl client, VirtualFile file) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        Either<TextDocumentSyncKind, TextDocumentSyncOptions> textDocumentSync = serverCapabilities != null ? serverCapabilities.getTextDocumentSync() : null;
        TextDocumentSyncOptions syncOptions = textDocumentSync != null ? textDocumentSync.getRight() : null;
        Either<Boolean, SaveOptions> save = syncOptions != null ? syncOptions.getSave() : null;
        if (save != null && Boolean.TRUE.equals(save.getLeft())) {
            return new SaveOptions(false);
        }
        if (save != null && save.getRight() != null) {
            return save.getRight();
        }
        // According to https://microsoft.github.io/language-server-protocol/specification/#textDocument_synchronization
        // `textDocumentSync` can be `TextDocumentSyncOptions` or `TextDocumentSyncKind`. When it is `TextDocumentSyncKind` there are no rules
        // that the client must send didSave notification, but VS Code does it in the case of TextDocumentSyncKind.Incremental and
        // TextDocumentSyncKind.Full. As good practice we should follow VS Code behavior.
        if (textDocumentSync != null && textDocumentSync.getLeft() != null && textDocumentSync.getLeft() != TextDocumentSyncKind.None) {
            return new SaveOptions(false);
        }
        TextDocumentSaveRegistrationOptions dynamicOptions = getDynamicCapabilityOptionsForFile(client, file, LspDynamicCapabilities.getDidSave());
        if (dynamicOptions != null) {
            return new SaveOptions(dynamicOptions.getIncludeText());
        }
        return null;
    }

    /**
     * @return {@code true} if the server says it is able to format at least some files;
     * {@code false} if the server doesn't support code formatting at all
     * @see #doesServerExplicitlyWantToFormatThisFile
     */
    public static boolean hasFullFileFormattingCapability(LspClientImpl client) {
        if (client.getDynamicCapabilities().hasCapability(LspDynamicCapabilities.getFormatting())) {
            return true;
        }
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        return serverCapabilities != null && serverCapabilities.getDocumentFormattingProvider() != null &&
            leftOrTrue(serverCapabilities.getDocumentFormattingProvider());
    }

    /**
     * @return {@code true} if the server says it is able to format at least some files;
     * {@code false} if the server doesn't support code range formatting at all
     * @see #doesServerExplicitlyWantToFormatThisFile
     */
    public static boolean hasRangeFormattingCapability(LspClientImpl client) {
        if (client.getDynamicCapabilities().hasCapability(LspDynamicCapabilities.getRangeFormatting())) {
            return true;
        }
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        return serverCapabilities != null && serverCapabilities.getDocumentRangeFormattingProvider() != null &&
            leftOrTrue(serverCapabilities.getDocumentRangeFormattingProvider());
    }

    /**
     * See docs for the {@code serverExplicitlyWantsToFormatThisFile} parameter in
     * {@code com.intellij.platform.lsp.api.customization.LspFormattingSupport.shouldFormatThisFileExclusivelyByServer}
     */
    @SuppressWarnings("unchecked")
    public static boolean doesServerExplicitlyWantToFormatThisFile(LspClientImpl client, VirtualFile file, boolean isFullFileFormatting) {
        Pair<String, Class<TextDocumentRegistrationOptions>> capabilityAndOptionsClass =
            (Pair<String, Class<TextDocumentRegistrationOptions>>) (Pair<String, ?>)
                (isFullFileFormatting ? LspDynamicCapabilities.getFormatting() : LspDynamicCapabilities.getRangeFormatting());
        // We intentionally don't check static server capabilities (serverCapabilities),
        // which can only say true/false but can't answer whether THIS specific file should be formatted exclusively by the server.
        // Erroneous `true` answer is very dangerous as it disables the IDE's internal formatter.
        return hasDynamicCapabilityToHandleThisFile(client, file, capabilityAndOptionsClass);
    }

    public static @Nullable List<String> getSignatureHelpTriggerCharacters(LspClientImpl client, VirtualFile file) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        List<String> staticTriggerCharacters = serverCapabilities != null && serverCapabilities.getSignatureHelpProvider() != null
            ? serverCapabilities.getSignatureHelpProvider().getTriggerCharacters()
            : null;
        SignatureHelpRegistrationOptions dynamicOptions = getDynamicCapabilityOptionsForFile(client, file, LspDynamicCapabilities.getSignatureHelp());
        List<String> dynamicTriggerCharacters = dynamicOptions != null ? dynamicOptions.getTriggerCharacters() : null;
        if (dynamicTriggerCharacters != null) {
            return dynamicTriggerCharacters;
        }
        return staticTriggerCharacters;
    }

    public static @Nullable List<String> getOnTypeFormattingTriggerCharacters(LspClientImpl client, VirtualFile file) {
        ServerCapabilities serverCapabilities = client.getServerCapabilities();
        DocumentOnTypeFormattingOptions staticOptions = serverCapabilities != null ? serverCapabilities.getDocumentOnTypeFormattingProvider() : null;
        List<String> staticTriggerChars = null;
        if (staticOptions != null) {
            staticTriggerChars = new ArrayList<>();
            staticTriggerChars.add(staticOptions.getFirstTriggerCharacter());
            if (staticOptions.getMoreTriggerCharacter() != null) {
                staticTriggerChars.addAll(staticOptions.getMoreTriggerCharacter());
            }
        }
        DocumentOnTypeFormattingRegistrationOptions dynamicOptions =
            getDynamicCapabilityOptionsForFile(client, file, LspDynamicCapabilities.getOnTypeFormatting());
        List<String> dynamicTriggerChars = null;
        if (dynamicOptions != null) {
            dynamicTriggerChars = new ArrayList<>();
            dynamicTriggerChars.add(dynamicOptions.getFirstTriggerCharacter());
            if (dynamicOptions.getMoreTriggerCharacter() != null) {
                dynamicTriggerChars.addAll(dynamicOptions.getMoreTriggerCharacter());
            }
        }
        return dynamicTriggerChars != null ? dynamicTriggerChars : staticTriggerChars;
    }

    private static <T extends TextDocumentRegistrationOptions> boolean hasDynamicCapabilityToHandleThisFile(
        LspClientImpl client,
        VirtualFile file,
        Pair<String, Class<T>> capabilityAndOptionsClass
    ) {
        return getDynamicCapabilityOptionsForFile(client, file, capabilityAndOptionsClass) != null;
    }

    private static <T extends TextDocumentRegistrationOptions> @Nullable T getDynamicCapabilityOptionsForFile(
        LspClientImpl client,
        VirtualFile file,
        Pair<String, Class<T>> capabilityAndOptionsClass
    ) {
        List<T> list = getDynamicCapabilityOptionsListForFile(client, file, capabilityAndOptionsClass);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * Every registration of the capability whose document selector matches {@code file}; a registration without a selector matches any file.
     */
    private static <T extends TextDocumentRegistrationOptions> List<T> getDynamicCapabilityOptionsListForFile(
        LspClientImpl client,
        VirtualFile file,
        Pair<String, Class<T>> capabilityAndOptionsClass
    ) {
        if (file.isDirectory()) {
            client.logWarn("Directory not expected here. Capability: " + capabilityAndOptionsClass.getFirst() + ", file: " + file.getPath());
            return List.of();
        }
        List<T> result = new ArrayList<>();
        for (T options : client.getDynamicCapabilities().getCapabilityRegistrationOptions(capabilityAndOptionsClass)) {
            if (documentSelectorMatches(client, options, file)) {
                result.add(options);
            }
        }
        return result;
    }

    private static boolean documentSelectorMatches(LspClientImpl client, TextDocumentRegistrationOptions registrationOptions, VirtualFile file) {
        List<DocumentFilter> documentSelector = registrationOptions.getDocumentSelector();
        if (documentSelector == null) {
            return true;
        }
        for (DocumentFilter filter : documentSelector) {
            if (filter.getScheme() != null && !Objects.equals(filter.getScheme(), "file")) {
                continue;
            }

            String language = filter.getLanguage();
            Either<String, RelativePattern> filterPattern = filter.getPattern();
            if (filterPattern != null && filterPattern.isRight()) {
                // A RelativePattern needs its baseUri resolved against the workspace folders. The IDE does not support it yet.
                client.logWarn("Ignoring the document filter, its pattern is relative: " + filterPattern.getRight());
                continue;
            }
            String pattern = filterPattern != null ? filterPattern.getLeft() : null;
            if (language == null && pattern == null) {
                continue;
            }
            if (language != null && !language.equals(client.getDescriptor().getLanguageId(file))) {
                continue;
            }
            if (pattern != null && !client.getGlobMatcher().pathMatches(file.getPath(), false, pattern, null)) {
                continue;
            }

            return true;
        }
        return false;
    }
}

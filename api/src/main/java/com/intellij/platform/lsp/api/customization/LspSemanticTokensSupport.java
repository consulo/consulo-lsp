// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

import consulo.codeEditor.DefaultLanguageHighlighterColors;
import consulo.codeEditor.HighlighterColors;
import consulo.colorScheme.TextAttributesKey;
import consulo.language.psi.PsiFile;
import org.eclipse.lsp4j.SemanticTokenModifiers;
import org.eclipse.lsp4j.SemanticTokenTypes;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * See LSP specification: <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_semanticTokens">Semantic Tokens</a>
 */
public non-sealed class LspSemanticTokensSupport extends LspSemanticTokensCustomizer {
    private final List<String> myTokenTypes = List.of(
        SemanticTokenTypes.Namespace,
        SemanticTokenTypes.Type,
        SemanticTokenTypes.Class,
        SemanticTokenTypes.Enum,
        SemanticTokenTypes.Interface,
        SemanticTokenTypes.Struct,
        SemanticTokenTypes.TypeParameter,
        SemanticTokenTypes.Parameter,
        SemanticTokenTypes.Variable,
        SemanticTokenTypes.Property,
        SemanticTokenTypes.EnumMember,
        SemanticTokenTypes.Event,
        SemanticTokenTypes.Function,
        SemanticTokenTypes.Method,
        SemanticTokenTypes.Macro,
        SemanticTokenTypes.Keyword,
        SemanticTokenTypes.Modifier,
        SemanticTokenTypes.Comment,
        SemanticTokenTypes.String,
        SemanticTokenTypes.Number,
        SemanticTokenTypes.Regexp,
        SemanticTokenTypes.Operator,
        SemanticTokenTypes.Decorator
    );

    private final List<String> myTokenModifiers = List.of(
        SemanticTokenModifiers.Declaration,
        SemanticTokenModifiers.Definition,
        SemanticTokenModifiers.Readonly,
        SemanticTokenModifiers.Static,
        SemanticTokenModifiers.Deprecated,
        SemanticTokenModifiers.Abstract,
        SemanticTokenModifiers.Async,
        SemanticTokenModifiers.Modification,
        SemanticTokenModifiers.Documentation,
        SemanticTokenModifiers.DefaultLibrary
    );

    /**
     * {@code True} means that the IDE should send the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#semanticTokens_fullRequest">textDocument/semanticTokens/full</a>
     * request to the server and use the received information for semantic code highlighting in the given file
     */
    public boolean shouldAskServerForSemanticTokens(PsiFile psiFile) {
        // Plain Text files and files with TextMate bundle-based basic syntax highlighting coming from TextMate bundles
        // do not have PSI and advanced code highlighting. So LSP-based semantic highlighting definitely makes sense for such files.
        // Other files are very likely to have PSI and first-class support, so they probably don’t need LSP-based highlighting.
        // The plugins are free to override this logic.
        String id = psiFile.getLanguage().getID();
        return id.equals("TEXT") || id.equals("textmate");
    }

    /**
     * Semantic token types that the plugin supports. This list is used as
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#semanticTokensClientCapabilities">SemanticTokensClientCapabilities.tokenTypes</a>
     * when sending the <a href="https://microsoft.github.io/language-server-protocol/specification/#initialize">initialize</a> request.
     *
     * @see com.intellij.platform.lsp.api.LspClientDescriptor#getClientCapabilities()
     */
    public List<String> getTokenTypes() {
        return myTokenTypes;
    }

    /**
     * Semantic token modifiers that the plugin supports. This list is used as
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#semanticTokensClientCapabilities">SemanticTokensClientCapabilities.tokenModifiers</a>
     * when sending the <a href="https://microsoft.github.io/language-server-protocol/specification/#initialize">initialize</a> request.
     *
     * @see com.intellij.platform.lsp.api.LspClientDescriptor#getClientCapabilities()
     */
    public List<String> getTokenModifiers() {
        return myTokenModifiers;
    }

    /**
     * Plugins may need to override this function to make sure that all token types supported by the LSP server
     * are reasonably mapped to {@link TextAttributesKey TextAttributeKeys}.
     * <p>
     * Basic syntax highlighting may be provided without using information from the LSP server, but for example, via TextMate bundles.
     * In this case, it's safe to return {@code null} from this function for the corresponding token types.
     */
    public @Nullable TextAttributesKey getTextAttributesKey(String tokenType, List<String> modifiers) {
        return switch (tokenType) {
            case SemanticTokenTypes.Namespace -> DefaultLanguageHighlighterColors.IDENTIFIER;
            case SemanticTokenTypes.Type -> DefaultLanguageHighlighterColors.IDENTIFIER;
            case SemanticTokenTypes.Class -> DefaultLanguageHighlighterColors.CLASS_NAME;
            case SemanticTokenTypes.Enum -> DefaultLanguageHighlighterColors.CLASS_NAME;
            case SemanticTokenTypes.Interface -> DefaultLanguageHighlighterColors.INTERFACE_NAME;
            case SemanticTokenTypes.Struct -> DefaultLanguageHighlighterColors.IDENTIFIER;
            case SemanticTokenTypes.TypeParameter -> DefaultLanguageHighlighterColors.IDENTIFIER;
            case SemanticTokenTypes.Parameter -> DefaultLanguageHighlighterColors.PARAMETER;
            case SemanticTokenTypes.Variable -> DefaultLanguageHighlighterColors.LOCAL_VARIABLE;
            case SemanticTokenTypes.Property -> modifiers.contains(SemanticTokenModifiers.Static)
                ? DefaultLanguageHighlighterColors.STATIC_FIELD
                : DefaultLanguageHighlighterColors.INSTANCE_FIELD;
            case SemanticTokenTypes.EnumMember -> modifiers.contains(SemanticTokenModifiers.Static)
                ? DefaultLanguageHighlighterColors.STATIC_FIELD
                : DefaultLanguageHighlighterColors.INSTANCE_FIELD;
            case SemanticTokenTypes.Event -> DefaultLanguageHighlighterColors.IDENTIFIER;
            case SemanticTokenTypes.Function -> DefaultLanguageHighlighterColors.FUNCTION_DECLARATION;
            case SemanticTokenTypes.Method -> modifiers.contains(SemanticTokenModifiers.Static)
                ? DefaultLanguageHighlighterColors.STATIC_METHOD
                : DefaultLanguageHighlighterColors.INSTANCE_METHOD;
            case SemanticTokenTypes.Macro -> DefaultLanguageHighlighterColors.IDENTIFIER;
            case SemanticTokenTypes.Keyword -> DefaultLanguageHighlighterColors.KEYWORD;
            case SemanticTokenTypes.Modifier -> HighlighterColors.TEXT;
            case SemanticTokenTypes.Comment -> DefaultLanguageHighlighterColors.LINE_COMMENT;
            case SemanticTokenTypes.String -> DefaultLanguageHighlighterColors.STRING;
            case SemanticTokenTypes.Number -> DefaultLanguageHighlighterColors.NUMBER;
            case SemanticTokenTypes.Regexp -> HighlighterColors.TEXT;
            case SemanticTokenTypes.Operator -> DefaultLanguageHighlighterColors.OPERATION_SIGN;
            case SemanticTokenTypes.Decorator -> HighlighterColors.TEXT;
            default -> null;
        };
    }
}

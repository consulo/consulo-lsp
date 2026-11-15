// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.access.RequiredWriteAction;
import consulo.language.editor.completion.CompletionParameters;
import consulo.language.editor.completion.lookup.LookupElement;
import consulo.language.editor.completion.lookup.LookupElementBuilder;
import consulo.language.editor.completion.lookup.LookupElementPresentation;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.image.Image;
import consulo.util.lang.StringUtil;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemLabelDetails;
import org.eclipse.lsp4j.CompletionItemTag;
import org.eclipse.lsp4j.InsertReplaceEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Handles <a href="https://microsoft.github.io/language-server-protocol/specification#completionItem">CompletionItem</a> objects
 * received from the LSP server.
 * Implementations may fine-tune the code completion behavior.
 * For example, they may filter out unneeded completion items or tweak completion item decoration.
 */
public non-sealed class LspCompletionSupport extends LspCompletionCustomizer {
    /**
     * This function is called when the user types {@code charTyped} in the editor,
     * but only if the typed character is listed in the server capabilities as one of the characters that should trigger code completion
     * (<a href="https://microsoft.github.io/language-server-protocol/specification/#completionOptions">CompletionOptions.triggerCharacters</a>).
     * According to the LSP specification, the IDE should initiate a code completion session when such a character is typed.
     * <p>
     * By default, this function returns {@code true}, which triggers a code completion session.
     * All applicable code completion contributors will be invoked to provide their completion items.
     * LSP-based code completion is controlled by the {@link #shouldRunCodeCompletion} function and other functions in this class.
     * <p>
     * Implementations may override this function and return {@code false} to ignore the presence of the typed character
     * in the server's {@code CompletionOptions.triggerCharacters}.
     */
    @RequiredUIAccess
    @RequiredWriteAction
    public boolean isTriggerCharacterRespected(char charTyped) {
        return true;
    }

    /**
     * This function is called when the IDE is running a code completion session.
     * Code completion could have been triggered, for example, by pressing a shortcut, by typing an identifier,
     * or by typing a 'trigger' character (see {@link #isTriggerCharacterRespected}).
     * This function controls whether the LSP server should be asked for code completion items at the given location.
     */
    @RequiredReadAction
    public boolean shouldRunCodeCompletion(CompletionParameters parameters) {
        return true;
    }

    /**
     * Controls whether the IDE should try to resolve {@code completionItem} before updating its lookup element presentation.
     * <p>
     * If this function returns {@code true}, and the LSP server advertises {@code CompletionOptions.resolveProvider}, the IDE sends the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#completionItem_resolve">completionItem/resolve</a> request.
     * <p>
     * Override this function and return {@code false} for completion items that should use only the data returned by {@code textDocument/completion},
     * for example, to avoid expensive or redundant resolve requests.
     */
    public boolean shouldResolveCompletionItem(CompletionItem completionItem) {
        return true;
    }

    /**
     * A code completion prefix is a sequence of characters to the left of the caret in the editor,
     * which is used by the IDE to filter completion items.
     * <p>
     * It's recommended that the LSP server explicitly provides
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#completionItem">CompletionItem.textEdit</a>.
     * In this case, a completion prefix is calculated based on the LSP server response.
     * <p>
     * If the LSP server doesn't provide {@code CompletionItem.textEdit} explicitly,
     * the IDE uses the result returned by this function to filter completion items received from the server.
     * <p>
     * Override this function if the LSP server doesn't provide {@code CompletionItem.textEdit}
     * and the {@code defaultPrefix} calculated by the IDE is incorrect.
     * <p>
     * Called in a background thread.
     *
     * @param defaultPrefix completion prefix calculated by the IDE using the {@code CompletionService.suggestPrefix} method
     */
    @RequiredReadAction
    public String getCompletionPrefix(CompletionParameters parameters, String defaultPrefix) {
        return defaultPrefix;
    }

    /**
     * Converts <a href="https://microsoft.github.io/language-server-protocol/specification#completionItem">CompletionItem</a> object
     * received from the LSP server into {@link LookupElement} object.
     * <p>
     * Note that implementations shouldn't manipulate the completion item presentation in this function.
     * To tune the presentation, override functions like {@link #getIcon}, {@link #isBold}, {@link #isStrikeout}, {@link #getTypeText}, or {@link #getTailText}.
     * In advanced use cases, plugins can override {@link #renderLookupElement}.
     * <p>
     * Some ideas that the overriding functions can implement:
     * <ul>
     * <li>return {@code null} if they want to ignore this {@code item}</li>
     * <li>tune completion item priority:
     *   <pre>
     *   PrioritizedLookupElement.withPriority(super.createLookupElement(parameters, item), priority)
     *   </pre></li>
     * <li>use {@code parameters} if they need to check the context, in which this code completion session has started.
     *   Note that {@link CompletionParameters#getOriginalFile() parameters.getOriginalFile()} might be an
     *   injected file,
     *   while all {@code lsp4j} entities (including {@code item}) always deal with the host file (also known as a top-level file).
     *   {@link consulo.language.inject.InjectedLanguageManager} helps to map offsets between an injected and a host file.</li>
     * </ul>
     */
    public @Nullable LookupElement createLookupElement(CompletionParameters parameters, CompletionItem item) {
        String filterText = item.getFilterText();
        String toUseForPrefixMatching = filterText != null ? StringUtil.convertLineSeparators(filterText) : item.getLabel();
        Either<TextEdit, InsertReplaceEdit> textEdit = item.getTextEdit();
        String toInsertRaw;
        if (textEdit != null) {
            toInsertRaw = textEdit.map(TextEdit::getNewText, InsertReplaceEdit::getNewText);
        }
        else if (item.getInsertText() != null) {
            toInsertRaw = item.getInsertText();
        }
        else {
            toInsertRaw = item.getLabel();
        }
        String toInsert = StringUtil.convertLineSeparators(toInsertRaw);

        LookupElementBuilder builder = LookupElementBuilder.create(item, toInsert);
        return toInsert.equals(toUseForPrefixMatching) ? builder : builder.withLookupString(toUseForPrefixMatching);
    }

    /**
     * Typically, plugins don't need to override this function as they can tune the completion item presentation
     * by overriding {@link #getIcon}, {@link #isBold}, {@link #isStrikeout}, {@link #getTypeText}, or {@link #getTailText}.
     * <p>
     * This function is usually called twice: first, for the initial {@link CompletionItem item},
     * and later for the <a href="https://microsoft.github.io/language-server-protocol/specification/#completionItem_resolve">resolved</a> one.
     */
    public void renderLookupElement(CompletionItem item, LookupElementPresentation presentation) {
        presentation.setItemText(item.getLabel());
        presentation.setIcon(getIcon(item));
        presentation.setItemTextBold(isBold(item));
        presentation.setStrikeout(isStrikeout(item));
        presentation.setTailText(getTailText(item), true);
        presentation.setTypeText(getTypeText(item));
        presentation.setTypeGrayed(true);
    }

    public CompletableFuture<Void> expensiveRenderLookupElement(CompletionItem completionItem, LookupElementPresentation presentation) {
        renderLookupElement(completionItem, presentation);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Compare with {@link LspSymbolKindCustomizer#getIcon} for similar, but incompatible icon mapping
     */
    protected @Nullable Image getIcon(CompletionItem item) {
        CompletionItemKind kind = item.getKind();
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case Text -> PlatformIconGroup.filetypesText();
            case Method -> PlatformIconGroup.nodesMethod();
            case Function -> PlatformIconGroup.nodesFunction();
            case Constructor -> PlatformIconGroup.nodesClass(); // no special icon
            case Field -> PlatformIconGroup.nodesField();
            case Variable -> PlatformIconGroup.nodesVariable();
            case Class -> PlatformIconGroup.nodesClass();
            case Interface -> PlatformIconGroup.nodesInterface();
            case Module -> null; // 'module' may mean different things
            case Property -> PlatformIconGroup.nodesProperty();
            case Unit -> null; // no standard icon
            case Value -> null; // no standard icon
            case Enum -> PlatformIconGroup.nodesEnum();
            case Keyword -> null; // icon is not needed
            case Snippet -> PlatformIconGroup.nodesTemplate();
            case Color -> PlatformIconGroup.gutterColors();
            case File -> PlatformIconGroup.filetypesAny_type();
            case Reference -> null; // no standard icon
            case Folder -> PlatformIconGroup.nodesFolder();
            case EnumMember -> PlatformIconGroup.nodesEnum(); // the same as for Enum
            case Constant -> PlatformIconGroup.nodesConstant();
            case Struct -> PlatformIconGroup.nodesStruct();
            case Event -> null; // no standard icon
            case Operator -> null; // no standard icon
            case TypeParameter -> PlatformIconGroup.nodesType();
        };
    }

    protected boolean isBold(CompletionItem item) {
        return item.getKind() == CompletionItemKind.Keyword;
    }

    @SuppressWarnings("deprecation")
    protected boolean isStrikeout(CompletionItem item) {
        // old LSP server implementations may use deprecated property `item.deprecated`
        return Boolean.TRUE.equals(item.getDeprecated()) ||
            (item.getTags() != null && item.getTags().contains(CompletionItemTag.Deprecated));
    }

    protected @Nullable String getTailText(CompletionItem item) {
        CompletionItemLabelDetails labelDetails = item.getLabelDetails();
        return labelDetails != null ? labelDetails.getDetail() : null;
    }

    protected @Nullable String getTypeText(CompletionItem item) {
        CompletionItemLabelDetails labelDetails = item.getLabelDetails();
        String description = labelDetails != null ? labelDetails.getDescription() : null;
        return description != null ? description : item.getDetail();
    }
}

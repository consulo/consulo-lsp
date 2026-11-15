package com.intellij.platform.lsp.impl.features.completion;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.intellij.platform.lsp.impl.LspClientImpl;
import consulo.document.Document;
import org.eclipse.lsp4j.ApplyKind;
import org.eclipse.lsp4j.CompletionApplyKind;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemDefaults;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionTriggerKind;
import org.eclipse.lsp4j.InsertReplaceEdit;
import org.eclipse.lsp4j.InsertReplaceRange;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class LspCompletionUtilKt {
    private LspCompletionUtilKt() {
    }

    public static CompletionContext createCompletionContext(
        LspClientImpl lspClient,
        Document document,
        int offset,
        boolean isAutoPopup
    ) {
        if (isAutoPopup) {
            String typedChar = getTypedChar(document, offset);
            if (typedChar != null && isCompletionTriggerCharacter(lspClient, typedChar)) {
                return new CompletionContext(CompletionTriggerKind.TriggerCharacter, typedChar);
            }
            return new CompletionContext(CompletionTriggerKind.Invoked);
        }
        return new CompletionContext(CompletionTriggerKind.Invoked);
    }

    public static CompletionList toCompletionList(Either<List<CompletionItem>, CompletionList> either) {
        return either.map(
            items -> new CompletionList(false, items),
            completionList -> {
                CompletionItemDefaults itemDefaults = completionList.getItemDefaults();
                if (itemDefaults != null) {
                    for (CompletionItem item : completionList.getItems()) {
                        applyItemDefaults(item, itemDefaults, completionList.getApplyKind());
                    }
                }
                return completionList;
            }
        );
    }

    private static @Nullable String getTypedChar(Document document, int offset) {
        return offset > 0 && offset <= document.getTextLength() ? String.valueOf(document.getCharsSequence().charAt(offset - 1)) : null;
    }

    private static boolean isCompletionTriggerCharacter(LspClientImpl lspClient, String typedChar) {
        ServerCapabilities serverCapabilities = lspClient.getServerCapabilities();
        CompletionOptions completionProvider = serverCapabilities != null ? serverCapabilities.getCompletionProvider() : null;
        List<String> triggerCharacters = completionProvider != null ? completionProvider.getTriggerCharacters() : null;
        return triggerCharacters != null && triggerCharacters.contains(typedChar);
    }

    private static void applyItemDefaults(CompletionItem item, CompletionItemDefaults itemDefaults, @Nullable CompletionApplyKind applyKind) {
        ApplyKind commitCharactersApplyKind = applyKind != null ? applyKind.getCommitCharacters() : null;
        if (commitCharactersApplyKind == ApplyKind.Merge) {
            item.setCommitCharacters(mergeCommitCharacters(item.getCommitCharacters(), itemDefaults.getCommitCharacters()));
        }
        else {
            item.setCommitCharacters(item.getCommitCharacters() != null ? item.getCommitCharacters() : itemDefaults.getCommitCharacters());
        }
        Either<Range, InsertReplaceRange> defaultEditRange = itemDefaults.getEditRange();
        if (item.getTextEdit() == null && defaultEditRange != null) {
            String textEditText = Objects.requireNonNullElse(item.getTextEditText(), item.getLabel());
            if (defaultEditRange.isLeft()) {
                item.setTextEdit(Either.forLeft(new TextEdit(Objects.requireNonNull(defaultEditRange.getLeft()), textEditText)));
            }
            else {
                InsertReplaceRange insertReplaceRange = Objects.requireNonNull(defaultEditRange.getRight());
                item.setTextEdit(Either.forRight(
                    new InsertReplaceEdit(textEditText, insertReplaceRange.getInsert(), insertReplaceRange.getReplace())));
            }
        }
        if (item.getInsertTextFormat() == null) {
            item.setInsertTextFormat(itemDefaults.getInsertTextFormat());
        }
        if (item.getInsertTextMode() == null) {
            item.setInsertTextMode(itemDefaults.getInsertTextMode());
        }
        ApplyKind dataApplyKind = applyKind != null ? applyKind.getData() : null;
        if (dataApplyKind == ApplyKind.Merge) {
            item.setData(mergeData(item.getData(), itemDefaults.getData()));
        }
        else {
            item.setData(isNullData(item.getData()) ? itemDefaults.getData() : item.getData());
        }
    }

    private static @Nullable List<String> mergeCommitCharacters(
        @Nullable List<String> itemCommitCharacters,
        @Nullable List<String> defaultCommitCharacters
    ) {
        if (itemCommitCharacters == null) {
            return defaultCommitCharacters;
        }
        if (defaultCommitCharacters == null) {
            return itemCommitCharacters;
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>(defaultCommitCharacters);
        merged.addAll(itemCommitCharacters);
        return new ArrayList<>(merged);
    }

    /**
     * Shallow merge: top-level properties of {@code itemData} overwrite the same-named properties of {@code defaultData}, nested values aren't merged.
     */
    private static @Nullable Object mergeData(@Nullable Object itemData, @Nullable Object defaultData) {
        if (isNullData(itemData)) {
            return defaultData;
        }
        if (itemData instanceof JsonObject itemJson && defaultData instanceof JsonObject defaultJson) {
            JsonObject result = defaultJson.deepCopy();
            for (Map.Entry<String, JsonElement> entry : itemJson.entrySet()) {
                result.add(entry.getKey(), entry.getValue());
            }
            return result;
        }
        return itemData;
    }

    private static boolean isNullData(@Nullable Object data) {
        return data == null || data instanceof JsonNull;
    }
}

package com.intellij.platform.lsp.impl.features.completion;

import com.intellij.platform.lsp.api.customization.LspCommandsSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.codeEditor.Editor;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.language.editor.completion.lookup.InsertHandler;
import consulo.language.editor.completion.lookup.InsertionContext;
import consulo.language.editor.completion.lookup.LookupElement;
import consulo.language.editor.completion.lookup.LookupElementDecorator;
import consulo.language.editor.template.TemplateManager;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class LspCompletionItemInsertHandler implements InsertHandler<LookupElement> {
    public static final LspCompletionItemInsertHandler INSTANCE = new LspCompletionItemInsertHandler();

    // Snippets do even have their own language with eBNF; however, it seems enough to parse them with regex for now
    // See: https://microsoft.github.io/language-server-protocol/specification/#snippet_syntax
    static final Pattern TEMPLATE_VARIABLE_REGEX = Pattern.compile("\\$\\{(\\d+):?([^{^}]*)}|\\$(\\d+)");
    static final int[] VARIABLE_NUMBER_GROUPS = {1, 3};
    static final int VARIABLE_DEFAULT_VALUE_GROUP = 2;
    static final String VARIABLE_NAME_PREFIX = "VAR_";
    static final String END_VARIABLE = "$END$";
    static final Map<String, Function<InsertionContext, @Nullable String>> LSP_VARIABLE_TRANSFORMATIONS = createLspVariableTransformations();

    private LspCompletionItemInsertHandler() {
    }

    private static Map<String, Function<InsertionContext, @Nullable String>> createLspVariableTransformations() {
        Map<String, Function<InsertionContext, @Nullable String>> map = new LinkedHashMap<>();
        map.put("$TM_CURRENT_LINE", context -> getLineContent(context));
        map.put("$TM_LINE_INDEX", context -> String.valueOf(context.getEditor().getCaretModel().getLogicalPosition().line));
        map.put("$TM_LINE_NUMBER", context -> String.valueOf(context.getEditor().getCaretModel().getLogicalPosition().line + 1));
        map.put("$TM_FILENAME", context -> {
            VirtualFile vFile = getVFile(context.getEditor());
            return vFile != null ? vFile.getName() : null;
        });
        map.put("$TM_FILENAME_BASE", context -> {
            VirtualFile vFile = getVFile(context.getEditor());
            return vFile != null ? vFile.getNameWithoutExtension() : null;
        });
        map.put("$TM_DIRECTORY", context -> {
            VirtualFile vFile = getVFile(context.getEditor());
            if (vFile == null) {
                return null;
            }
            VirtualFile directory = !vFile.isDirectory() ? vFile.getParent() : vFile;
            return directory != null ? toNioPathString(directory) : null;
        });
        map.put("$TM_FILEPATH", context -> {
            VirtualFile vFile = getVFile(context.getEditor());
            return vFile != null ? toNioPathString(vFile) : null;
        });

        //"$TM_CURRENT_WORD" to { context: InsertionContext -> }, // The platform removes the completion prefix from InsertionContext.document.text
        // before calling LspCompletionItemInsertHandler.handleInsert(), so it is not possible to follow VS Code behavior for $TM_CURRENT_WORD.

        //"$TM_SELECTED_TEXT" to { context: InsertionContext -> }, // In the runtime the platform deletes selected text from the context before
        // LspCompletionItemInsertHandler calls handleInsert() method. The only solution to get it here is to add UserData in
        // LspCompletionContributor to the LookupElement (at this point selected text still exists), but it is a too complex approach for this
        // small feature. So, it could be implemented only by user request.
        return map;
    }

    @Override
    public void handleInsert(InsertionContext context, LookupElement lookupElement) {
        handleAdditionalTextEdits(context, lookupElement);
        if (lookupElement instanceof LookupElementDecorator<?> decorator) {
            decorator.getDelegate().handleInsert(context);
        }
        handleSnippetFormat(context, lookupElement);
        CompletionItem completionItem = getLsp4jCompletionItem(lookupElement);
        Command command = completionItem != null ? completionItem.getCommand() : null;
        if (command != null) {
            LspClientImpl lspClient = getLspClient(lookupElement);
            VirtualFile contextFile = context.getFile().getVirtualFile();
            if (lspClient != null && contextFile != null && lspClient.getDescriptor().getLspCustomization().getCommandsCustomizer()
                instanceof LspCommandsSupport lspCommandsSupport) {
                lspCommandsSupport.executeCommand(lspClient, contextFile, command);
            }
        }
    }

    private void handleAdditionalTextEdits(InsertionContext context, LookupElement lookupElement) {
        CompletionItem completionItem = getLsp4jCompletionItem(lookupElement);
        List<TextEdit> edits = completionItem != null ? completionItem.getAdditionalTextEdits() : null;
        if (completionItem == null || edits == null) {
            return;
        }
        Range mainEditRange = completionItem.getTextEdit() != null
            ? completionItem.getTextEdit().map(TextEdit::getRange, it -> it.getInsert())
            : null;
        Position startPosition = mainEditRange != null
            ? mainEditRange.getStart()
            : Lsp4jUtilKt.getLsp4jPosition(context.getDocument(), context.getStartOffset());

        // Full handling of additionalTextEdits is complicated
        // because we need to agree offsets returned by the language servers with the built-in IJ Platform completion.
        // For now, it's straightforward to apply offsets that come before the main textEdit offset, since they are not affected by the above.
        List<TextEdit> editsBefore = new ArrayList<>();
        for (TextEdit edit : edits) {
            if (edit.getRange().getStart().getLine() < startPosition.getLine() ||
                (edit.getRange().getStart().getLine() == startPosition.getLine() &&
                    edit.getRange().getStart().getCharacter() <= startPosition.getCharacter())) {
                editsBefore.add(edit);
            }
        }
        Lsp4jUtilKt.applyTextEdits(context.getDocument(), editsBefore);
    }

    private void handleSnippetFormat(InsertionContext context, LookupElement lookupElement) {
        CompletionItem completionItem = getLsp4jCompletionItem(lookupElement);
        if (completionItem == null || completionItem.getInsertTextFormat() != InsertTextFormat.Snippet) {
            return;
        }

        context.getDocument().replaceString(context.getStartOffset(), context.getTailOffset(), "");
        TemplateManager.getInstance(context.getProject())
            .startTemplate(
                context.getEditor(),
                new SnippetToTemplateConverter(context.getProject(), lookupElement.getLookupString())
                    .computeTemplate(context)
            );
    }

    private static String getLineContent(InsertionContext context) {
        Document document = context.getDocument();
        int line = document.getLineNumber(context.getTailOffset());
        return document.getCharsSequence().subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString();
    }

    private static @Nullable VirtualFile getVFile(Editor editor) {
        return FileDocumentManager.getInstance().getFile(editor.getDocument());
    }

    private static @Nullable String toNioPathString(VirtualFile file) {
        Path nioPath = file.getFileSystem().getNioPath(file);
        return nioPath != null ? nioPath.toString() : null;
    }

    private static @Nullable CompletionItem getLsp4jCompletionItem(LookupElement lookupElement) {
        Object object = lookupElement.getObject();
        if (object instanceof LspCompletionObject completionObject) {
            return completionObject.getCompletionItem();
        }
        return object instanceof CompletionItem completionItem ? completionItem : null; // for SnippetParsingTest
    }

    private static @Nullable LspClientImpl getLspClient(LookupElement lookupElement) {
        return lookupElement.getObject() instanceof LspCompletionObject completionObject ? completionObject.getLspClient() : null;
    }
}

package com.intellij.platform.lsp.impl.features.completion;

import com.intellij.platform.lsp.api.customization.LspCompletionSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.dumb.DumbAware;
import consulo.application.progress.ProgressManager;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.language.Language;
import consulo.language.editor.completion.CompletionContributor;
import consulo.language.editor.completion.CompletionParameters;
import consulo.language.editor.completion.CompletionResultSet;
import consulo.language.editor.completion.lookup.LookupElement;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.language.inject.InjectedLanguageManager;
import consulo.language.psi.PsiFile;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextEdit;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Consulo: upstream order is {@code "last, before wordCompletion, before TextMateCompletionContributor"}; Consulo has no
 * {@code TextMateCompletionContributor}.
 */
@ExtensionImpl(id = "LspCompletionContributor", order = "last, before wordCompletion")
public class LspCompletionContributor extends CompletionContributor implements DumbAware {
    @Override
    @RequiredReadAction
    public void fillCompletionVariants(CompletionParameters parameters, CompletionResultSet result) {
        PsiFile psiFile = parameters.getOriginalFile();
        Project project = psiFile.getProject();
        if (project.isDefault()) {
            return; // Welcome screen -> some project wizards may have completion in its fields
        }

        VirtualFile virtualFile = psiFile.getOriginalFile().getVirtualFile();
        if (virtualFile == null) {
            return;
        }
        VirtualFile file = virtualFile instanceof VirtualFileWindow virtualFileWindow ? virtualFileWindow.getDelegate() : virtualFile;
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            return;
        }
        int offset = InjectedLanguageManager.getInstance(project).injectedToHost(psiFile, parameters.getOffset());

        for (LspClientImpl client : LspClientManagerImpl.getInstanceImpl(project).getClientsWithThisFileOpen(file)) {
            ProgressManager.checkCanceled();

            ServerCapabilities serverCapabilities = client.getServerCapabilities();
            if (serverCapabilities == null || serverCapabilities.getCompletionProvider() == null) {
                continue;
            }
            if (!(client.getDescriptor().getLspCustomization().getCompletionCustomizer() instanceof LspCompletionSupport completionSupport)) {
                continue;
            }
            if (!completionSupport.shouldRunCodeCompletion(parameters)) {
                continue;
            }

            // LSP server wants to handle code completion, so disable less smart WordCompletionContributor for the current completion session
            // TODO Consulo: BaseCompletionService.FORBID_WORD_COMPLETION does not exist in Consulo; the word completion of Consulo
            //  runs only for an explicitly invoked completion (or in dumb mode) and cannot be disabled by a contributor

            CompletionList completionList = client.getRequestExecutor().getCompletionList(file, offset, parameters.isAutoPopup());
            if (completionList == null) {
                continue;
            }

            if (completionList.isIncomplete()) {
                result.restartCompletionOnAnyPrefixChange();
            }

            if (completionList.getItems().isEmpty()) {
                continue;
            }

            String defaultPrefix = result.getPrefixMatcher().getPrefix();
            String prefix = completionSupport.getCompletionPrefix(parameters, defaultPrefix);
            CompletionResultSet originalResultSet = !prefix.equals(defaultPrefix) ? result.withPrefixMatcher(prefix) : result;

            processCompletionItemsImpl(client, document, offset, originalResultSet, completionList.getItems(),
                it -> completionSupport.createLookupElement(parameters, it));
        }
    }

    @Override
    public Language getLanguage() {
        return Language.ANY;
    }

    /**
     * @param createLookupElement converts an instance of {@code T} to the {@link LookupElement}, or returns `null` if the completion item is not needed.
     *                            Created `LookupElement` MUST return the corresponding {@link CompletionItem} object from its {@link LookupElement#getObject}.
     */
    public static <T> void processCompletionItemsImpl(
        LspClientImpl lspClient,
        Document document,
        int offset,
        CompletionResultSet originalResultSet,
        List<T> items,
        Function<T, @Nullable LookupElement> createLookupElement
    ) {
        // Different CompletionItems may have different insert ranges, which means the need to use different prefix matchers.
        CompletionResultSet currentResultSet = originalResultSet;
        @Nullable Range currentInsertRange = null;
        Position completionPosition = Lsp4jUtilKt.getLsp4jPosition(document, offset);

        // We should not overflow the LSP server with resolveCompletionItem commands, because it blocks
        // other commands and may lead to unresponsiveness of the server if completion item resolve takes a lot of time.
        // Not every LSP supports command cancellation properly.
        Semaphore requestSemaphore = new Semaphore(2);
        for (T item : items) {
            LookupElement lookupElement = createLookupElement.apply(item);
            if (lookupElement == null) {
                continue;
            }
            if (!(lookupElement.getObject() instanceof CompletionItem completionItem)) {
                continue;
            }
            Range insertRange = getInsertRange(completionItem);
            if (!Objects.equals(insertRange, currentInsertRange)) {
                currentInsertRange = insertRange;
                if (insertRange == null) {
                    currentResultSet = originalResultSet;
                }
                else {
                    String prefix = getCompletionPrefix(document, completionPosition, insertRange);
                    currentResultSet = prefix != null ? originalResultSet.withPrefixMatcher(prefix) : originalResultSet;
                }
            }

            currentResultSet.addElement(new LspLookupElementDecorator(lspClient, requestSemaphore, lookupElement, completionItem));
        }
    }

    private static @Nullable Range getInsertRange(CompletionItem item) {
        return item.getTextEdit() != null ? item.getTextEdit().map(TextEdit::getRange, it -> it.getInsert()) : null;
    }

    private static @Nullable String getCompletionPrefix(Document document, Position completionPosition, Range insertRange) {
        if (insertRange.getStart().getLine() != insertRange.getEnd().getLine() ||
            insertRange.getStart().getLine() != completionPosition.getLine() ||
            insertRange.getStart().getCharacter() > completionPosition.getCharacter() ||
            insertRange.getEnd().getCharacter() < completionPosition.getCharacter()) {
            // The data from the server doesn't follow the specification requirements.
            return null;
        }

        int lineStartOffset = document.getLineStartOffset(completionPosition.getLine());
        int start = lineStartOffset + insertRange.getStart().getCharacter();
        int end = lineStartOffset + completionPosition.getCharacter();
        return document.getCharsSequence().subSequence(start, end).toString();
    }
}

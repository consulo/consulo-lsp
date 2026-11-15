package com.intellij.platform.lsp.impl.features.completion;

import consulo.annotation.component.ExtensionImpl;
import consulo.language.editor.completion.CompletionLocation;
import consulo.language.editor.completion.CompletionWeigher;
import consulo.language.editor.completion.lookup.LookupElement;
import org.jspecify.annotations.Nullable;

/**
 * Sorts LSP-based completion items by
 * <a href="https://microsoft.github.io/language-server-protocol/specification/#completionItem">CompletionItem.sortText</a>
 *
 * <h3>Test coverage</h3>
 * `com.intellij.tailwind.lsp.TailwindLspCompletionTest.testTypingInHtmlNotInPackage`
 *
 * <h3>Implementation details</h3>
 * Another way to apply LSP-based sorting is to use the sorter right in the {@link LspCompletionContributor} could be like this:
 * <pre>{@code
 * val weigher = object : LookupElementWeigher("lspSorter", / * negated = * / false, / * dependsOnPrefix = * / false) {
 *   override fun weigh(element: LookupElement): Comparable<String> =
 *     (element.`object` as? LspCompletionObject)?.completionItem?.sortText
 *     ?: ""
 * }
 * val sorter = CompletionSorter.defaultSorter(parameters, result.prefixMatcher).weighAfter("priority", weigher)
 * val sortingResultSet = result.withRelevanceSorter(sorter)
 * processCompletionItemsImpl(server, document, offset, sortingResultSet, completionList.items) {...}
 * }</pre>
 * but that doesn't work, unfortunately.
 * That way, the PrioritizedLookupElement returned from LspCompletionSupport.createLookupElement
 * doesn't help to bubble up the items in the completion list.
 */
@ExtensionImpl(id = "LspCompletionWeigher", order = "after priority, before prefix")
public class LspCompletionWeigher extends CompletionWeigher {
    @Override
    public @Nullable Comparable<ReverseComparableString> weigh(LookupElement element, CompletionLocation location) {
        if (!(element.getObject() instanceof LspCompletionObject completionObject)) {
            return null;
        }
        String sortText = completionObject.getCompletionItem().getSortText();
        if (sortText == null) {
            return null;
        }
        return new ReverseComparableString(sortText);
    }
}

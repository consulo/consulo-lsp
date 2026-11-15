package com.intellij.platform.lsp.impl.features.highlighting;

import com.intellij.platform.lsp.impl.cache.LspPerFileCache;
import consulo.project.Project;

import java.util.List;

public class LspDocumentHighlightCache extends LspPerFileCache<Integer, List<TextRangeAndHighlightKind>> {
    public LspDocumentHighlightCache(Project project) {
        super(
            project,
            false,
            (storedOffset, storedValue, queriedOffset) ->
                storedOffset.equals(queriedOffset) || storedValue.stream().anyMatch(it -> it.getTextRange().contains(queriedOffset))
        );
    }
}

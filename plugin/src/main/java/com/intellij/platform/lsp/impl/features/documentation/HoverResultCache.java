package com.intellij.platform.lsp.impl.features.documentation;

import com.intellij.platform.lsp.impl.cache.LspPerFileCache;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

public class HoverResultCache extends LspPerFileCache<Integer, TextRangeAndMarkupContent> {
    public HoverResultCache(Project project) {
        super(project, false, (storedKey, storedValue, queriedOffset) -> storedValue.getTextRange().contains(queriedOffset));
    }

    /**
     * Must be called on a background thread.
     */
    @Override
    public @Nullable TextRangeAndMarkupContent getOrCompute(
        VirtualFile file,
        Integer key,
        Supplier<@Nullable TextRangeAndMarkupContent> compute
    ) {
        if (file instanceof VirtualFileWindow) {
            Logger.getInstance(HoverResultCache.class).error("VirtualFileWindow not expected here");
            return null;
        }
        return super.getOrCompute(file, key, compute);
    }
}

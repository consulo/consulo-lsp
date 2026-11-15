// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.navigation;

import com.intellij.platform.lsp.impl.cache.LspPerFileCache;
import consulo.project.Project;

/**
 * Caches a {@code textDocument/definition} or a {@code textDocument/typeDefinition} response.
 * <p>
 * Ctrl+hover asks for the declaration under the mouse pointer on every mouse move,
 * so a lookup also hits the cache while the pointer stays within the origin range reported by the server.
 * A response without an origin range (including an empty one) is only reused at the very same offset.
 */
public class LspDefinitionCache extends LspPerFileCache<Integer, TextRangeAndLocationLinks> {
    public LspDefinitionCache(Project project) {
        super(
            project,
            false,
            (storedOffset, storedValue, queriedOffset) ->
                storedValue.getTextRange().contains(queriedOffset) || storedOffset.equals(queriedOffset)
        );
    }
}

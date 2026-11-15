// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

import consulo.annotation.access.RequiredReadAction;
import consulo.document.Document;
import consulo.document.util.TextRange;
import consulo.language.psi.PsiFile;
import org.jspecify.annotations.Nullable;

/**
 * Enables LSP rename support.
 */
public non-sealed class LspRenameSupport extends LspRenameCustomizer {
    public boolean shouldRunRename(PsiFile psiFile) {
        // Files with PSI structure are handled by PsiElementRenameHandler out of the box.
        // Plain text and TextMate files lack PSI structure, so LSP rename is used for them.
        // Plugins may override this logic as needed.
        String id = psiFile.getLanguage().getID();
        return id.equals("TEXT") || id.equals("textmate");
    }

    /**
     * Returns the text range of the renameable word at the given offset, or null if rename is not available.
     * <p>
     * This method is used as a fallback when:
     * <ul>
     * <li>The LSP server doesn't support {@code textDocument/prepareRename}</li>
     * <li>The LSP server returns {@code defaultBehavior: true} in the prepare rename response</li>
     * </ul>
     * <b>Important</b>: This is the place to check for keywords, built-in identifiers, or other non-renameable elements.
     * If null is returned, the rename operation will be aborted and nothing will happen next.
     * <p>
     * The default implementation uses word boundaries based on the document's content.
     * By default, {@link Character#isJavaIdentifierPart} is used as it works well for most languages.
     * <p>
     * Override {@link #getRenameableRangeAtOffset} to provide language-specific logic for determining renameable identifiers.
     */
    @RequiredReadAction
    public @Nullable TextRange getRenameableRangeAtOffset(Document document, int offset) {
        CharSequence text = document.getCharsSequence();
        if (offset < 0 || offset > text.length()) {
            return null;
        }

        int start = offset;
        int end = offset;

        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }

        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
            end++;
        }

        // No word found
        if (start == end) {
            return null;
        }

        return new TextRange(start, end);
    }
}

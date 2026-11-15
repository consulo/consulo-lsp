// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.navigation;

import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.language.psi.PsiFile;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

final class LspGotoImplementationKt {
    private LspGotoImplementationKt() {
    }

    /**
     * Returns the word around the offset, or null when the offset does not touch a word.
     */
    static @Nullable String wordAt(PsiFile psiFile, int offsetInFile) {
        VirtualFile virtualFile = psiFile.getVirtualFile();
        Document document = virtualFile != null ? FileDocumentManager.getInstance().getCachedDocument(virtualFile) : null;
        if (document == null) {
            return null;
        }
        CharSequence text = document.getCharsSequence();
        int start = Math.max(0, Math.min(offsetInFile, text.length()));
        int end = start;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
            end++;
        }
        return start < end ? text.subSequence(start, end).toString() : null;
    }
}

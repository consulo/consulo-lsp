// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

import consulo.language.psi.PsiFile;

/**
 * Base class for customizing LSP highlight usage behavior in language plugins.
 * See LSP specification: <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_documentHighlight">Document Highlights</a>
 */
public non-sealed class LspDocumentHighlightsSupport extends LspDocumentHighlightsCustomizer {
    /**
     * Determines whether document highlights should be processed for the given file.
     */
    public boolean shouldAskServerForDocumentHighlights(PsiFile psiFile) {
        // Plain Text files and files with TextMate bundle-based basic syntax highlighting coming from TextMate bundles
        // do not have PSI and advanced language support. So LSP-based document highlighting (highlighting usages of the symbol under the caret)
        // definitely makes sense for such files. Other files are very likely to have PSI and first-class support with
        // built-in identifier highlighting, so they probably don't need LSP-based document highlights.
        // The plugins are free to override this logic.

        String id = psiFile.getLanguage().getID();
        return id.equals("TEXT") || id.equals("textmate");
    }
}

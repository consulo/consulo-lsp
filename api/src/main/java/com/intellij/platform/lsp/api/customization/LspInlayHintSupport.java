// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.InlayHint;

/**
 * Base class for customizing LSP inlay hints behavior in language plugins.
 * <p>
 * This is the primary integration point for language plugins that want to provide
 * settings and customization for LSP inlay hints. Language plugins should extend
 * this class and override the relevant methods to implement their own settings logic.
 */
public non-sealed class LspInlayHintSupport extends LspInlayHintCustomizer {
    /**
     * Determines whether inlay hints should be processed for the given file.
     * This is called before processing individual hint types.
     *
     * @param file the file to check for inlay hints processing
     * @return true if inlay hints should be processed for this file, false otherwise
     */
    public boolean shouldAskServerForInlayHints(VirtualFile file) {
        return true;
    }

    /**
     * Determines whether a specific inlay hint should be processed and displayed.
     * This method provides access to the full InlayHint object, allowing custom
     * filtering logic based on label, kind, or any other hint properties.
     *
     * @param inlayHint the LSP InlayHint object containing all hint information
     * @return true if the hint should be processed and displayed, false to filter it out
     * @deprecated Use {@link #shouldDisplayInlayHint} instead
     */
    @Deprecated
    public boolean shouldApplyInlayHint(InlayHint inlayHint) {
        return true;
    }

    /**
     * Determines whether a specific inlay hint should be processed and displayed.
     * This method provides access to the full InlayHint object, allowing custom
     * filtering logic based on label, kind, or any other hint properties.
     *
     * @param file      the file for which the hint is being processed
     * @param inlayHint the LSP InlayHint object containing all hint information
     * @return true if the hint should be processed and displayed, false to filter it out
     */
    @SuppressWarnings("deprecation")
    public boolean shouldDisplayInlayHint(VirtualFile file, InlayHint inlayHint) {
        return shouldApplyInlayHint(inlayHint);
    }

    /**
     * Maximum number of characters allowed for a single inlay hint label.
     * Applies to both simple (string) and composite labels (sum of parts).
     * Values are sanitized by the platform to a safe range.
     */
    public int getMaxInlayHintChars() {
        return 42;
    }
}

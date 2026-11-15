package com.intellij.platform.lsp.impl.features.inlayCommon;

import consulo.codeEditor.Editor;
import consulo.language.editor.inlay.DeclarativeInlayTreeSink;

/**
 * One inline inlay to display — an inlay hint or a document-color swatch.
 */
public interface LspInlayItem {
    /**
     * The document offset at which the inline inlay is anchored.
     */
    int getOffset();

    /**
     * A value-equality key for the rendered content. MUST exclude {@link #getOffset() offset} (the inlay moves with edits on its own).
     * Identities of different features are type-distinct, so a hint and a swatch at the same offset never cross-match.
     */
    Object getIdentity();

    /**
     * Builds the presentation.
     * <p>
     * The item adds its presentation to the declarative inlay hints {@code sink} (called under read action by the declarative
     * inlay pass).
     */
    void buildPresentation(Editor editor, DeclarativeInlayTreeSink sink);
}

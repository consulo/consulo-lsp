package com.intellij.platform.lsp.impl.features.intention;

import consulo.annotation.component.ExtensionImpl;
import consulo.language.editor.intention.IntentionMetaData;

@ExtensionImpl
@IntentionMetaData(ignoreId = "platform.lsp.intention", categories = "LSP", fileExtensions = "txt")
public class LspIntention7 extends LspIntentionActionWrapper {
    public LspIntention7() {
        super(7);
    }
}

package com.intellij.platform.lsp.impl.features.intention;

import consulo.annotation.component.ExtensionImpl;
import consulo.language.editor.intention.IntentionMetaData;

@ExtensionImpl
@IntentionMetaData(ignoreId = "platform.lsp.intention", categories = "LSP", fileExtensions = "txt")
public class LspIntention13 extends LspIntentionActionWrapper {
    public LspIntention13() {
        super(13);
    }
}

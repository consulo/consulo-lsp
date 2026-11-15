// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

/**
 * Handles <a href="https://microsoft.github.io/language-server-protocol/specification#signatureHelp">SignatureHelp</a> objects
 * received from the LSP server.
 * Implementations may fine-tune the signature help behavior.
 */
public non-sealed class LspSignatureHelpSupport extends LspSignatureHelpCustomizer {
    /**
     * This function is called when the user types {@code charTyped} in the editor,
     * but only if the typed character is listed in the server capabilities as one of the characters that should trigger signature help
     * (<a href="https://microsoft.github.io/language-server-protocol/specification/#signatureHelpOptions">SignatureHelpOptions.triggerCharacters</a>).
     * According to the LSP specification, the IDE should initiate a signature help session when such a character is typed.
     * <p>
     * By default, this function returns {@code true}, which triggers a signature help session.
     * <p>
     * Implementations may override this function and return {@code false} to ignore the presence of the typed character
     * in the server's {@code SignatureHelpOptions.triggerCharacters}.
     */
    public boolean isTriggerCharacterRespected(char charTyped) {
        return true;
    }
}

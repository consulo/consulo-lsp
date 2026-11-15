package com.intellij.platform.lsp.impl.connector;

public class LspInitializationException extends RuntimeException {
    public LspInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}

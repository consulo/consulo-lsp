package com.intellij.platform.lsp.impl.connector;

import com.intellij.platform.lsp.api.Lsp4jServer;

public interface Lsp4jServerWrapperCreator {
    Lsp4jServer wrapLsp4jServer(Lsp4jServer lsp4jServer);
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

@FunctionalInterface
public interface Lsp4jServerWrapper {
    Lsp4jServer wrapLsp4jServer(LspClient lspClient, Lsp4jServer lsp4jServer);
}

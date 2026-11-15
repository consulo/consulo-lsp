// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

/**
 * @see LspClient#getState()
 */
public enum LspServerState {
    /**
     * Initial state.
     * The state changes to {@link #Running} when the IDE receives a response to the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#initialize">initialize</a> request.
     */
    Initializing,

    /**
     * Ready to handle requests and notifications from the IDE.
     * Technically, it means that the IDE has already received a response to the very first
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#initialize">initialize</a> request.
     */
    Running,

    ShutdownNormally,

    ShutdownUnexpectedly,
}

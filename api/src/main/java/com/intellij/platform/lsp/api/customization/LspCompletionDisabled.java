// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

public final class LspCompletionDisabled extends LspCompletionCustomizer {
    public static final LspCompletionDisabled INSTANCE = new LspCompletionDisabled();

    private LspCompletionDisabled() {
    }
}

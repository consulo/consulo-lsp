// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

public final class LspCodeActionsDisabled extends LspCodeActionsCustomizer {
    public static final LspCodeActionsDisabled INSTANCE = new LspCodeActionsDisabled();

    private LspCodeActionsDisabled() {
    }
}

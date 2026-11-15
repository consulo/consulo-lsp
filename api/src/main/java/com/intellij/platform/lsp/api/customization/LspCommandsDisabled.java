// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

public final class LspCommandsDisabled extends LspCommandsCustomizer {
    public static final LspCommandsDisabled INSTANCE = new LspCommandsDisabled();

    private LspCommandsDisabled() {
    }
}

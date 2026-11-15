// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

public final class LspSignatureHelpDisabled extends LspSignatureHelpCustomizer {
    public static final LspSignatureHelpDisabled INSTANCE = new LspSignatureHelpDisabled();

    private LspSignatureHelpDisabled() {
    }
}

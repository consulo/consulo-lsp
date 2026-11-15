// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

public final class LspOptimizeImportsDisabled extends LspOptimizeImportsCustomizer {
    public static final LspOptimizeImportsDisabled INSTANCE = new LspOptimizeImportsDisabled();

    private LspOptimizeImportsDisabled() {
    }
}

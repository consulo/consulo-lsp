// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

public final class LspFindReferencesDisabled extends LspFindReferencesCustomizer {
    public static final LspFindReferencesDisabled INSTANCE = new LspFindReferencesDisabled();

    private LspFindReferencesDisabled() {
    }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

public enum LspInheritanceMarkerKind {
    /**
     * An interface member that has implementations. Computed with {@code textDocument/implementation}.
     */
    IMPLEMENTED_METHOD,

    /**
     * A class method that is overridden in a subclass. Computed with {@code textDocument/implementation}.
     */
    OVERRIDDEN_METHOD,

    /**
     * An interface that has implementations. Computed with {@code typeHierarchy/subtypes}.
     */
    IMPLEMENTED_CLASS,

    /**
     * A class that has subtypes. Computed with {@code typeHierarchy/subtypes}.
     */
    SUBCLASSED_CLASS,
}

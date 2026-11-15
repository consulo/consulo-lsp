// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.SymbolKind;

import java.util.List;

/**
 * One computed gutter marker: the relation kind, the symbol it decorates, and the navigation targets.
 */
public class LspInheritanceMarker {
    private final LspInheritanceMarkerKind myKind;
    private final String mySymbolName;
    private final SymbolKind mySymbolKind;
    private final List<Location> myTargets;

    public LspInheritanceMarker(LspInheritanceMarkerKind kind, String symbolName, SymbolKind symbolKind, List<Location> targets) {
        myKind = kind;
        mySymbolName = symbolName;
        mySymbolKind = symbolKind;
        myTargets = targets;
    }

    public LspInheritanceMarkerKind getKind() {
        return myKind;
    }

    public String getSymbolName() {
        return mySymbolName;
    }

    public SymbolKind getSymbolKind() {
        return mySymbolKind;
    }

    public List<Location> getTargets() {
        return myTargets;
    }
}

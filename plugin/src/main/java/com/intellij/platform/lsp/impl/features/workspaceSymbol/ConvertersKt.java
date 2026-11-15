// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.workspaceSymbol;

import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolTag;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.ArrayList;
import java.util.List;

public final class ConvertersKt {
    private ConvertersKt() {
    }

    @SuppressWarnings("deprecation")
    public static WorkspaceSymbol toWorkspaceSymbol(SymbolInformation symbolInformation) {
        WorkspaceSymbol ws = new WorkspaceSymbol();
        ws.setName(symbolInformation.getName());
        ws.setKind(symbolInformation.getKind());
        ws.setContainerName(symbolInformation.getContainerName());
        ws.setLocation(Either.forLeft(symbolInformation.getLocation()));

        List<SymbolTag> tags = symbolInformation.getTags();
        if (Boolean.TRUE.equals(symbolInformation.getDeprecated()) && (tags == null || !tags.contains(SymbolTag.Deprecated))) {
            if (tags == null) {
                tags = new ArrayList<>();
            }
            tags.add(SymbolTag.Deprecated);
        }
        ws.setTags(tags);

        return ws;
    }
}

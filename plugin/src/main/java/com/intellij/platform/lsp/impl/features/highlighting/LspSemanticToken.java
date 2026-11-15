package com.intellij.platform.lsp.impl.features.highlighting;

import java.util.List;

public class LspSemanticToken {
    private final String myTokenType;
    private final List<String> myTokenModifiers;

    public LspSemanticToken(String tokenType, List<String> tokenModifiers) {
        myTokenType = tokenType;
        myTokenModifiers = tokenModifiers;
    }

    public String getTokenType() {
        return myTokenType;
    }

    public List<String> getTokenModifiers() {
        return myTokenModifiers;
    }
}

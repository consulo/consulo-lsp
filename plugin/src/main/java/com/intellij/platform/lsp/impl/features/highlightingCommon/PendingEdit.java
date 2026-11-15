package com.intellij.platform.lsp.impl.features.highlightingCommon;

public class PendingEdit {
    private final int myOffset;
    private final int myOldLength;
    private final int myNewLength;

    public PendingEdit(int offset, int oldLength, int newLength) {
        myOffset = offset;
        myOldLength = oldLength;
        myNewLength = newLength;
    }

    public int getOffset() {
        return myOffset;
    }

    public int getOldLength() {
        return myOldLength;
    }

    public int getNewLength() {
        return myNewLength;
    }
}

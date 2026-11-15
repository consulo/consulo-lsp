package com.intellij.platform.lsp.impl.features.completion;

public class ReverseComparableString implements Comparable<ReverseComparableString> {
    private final String myString;

    public ReverseComparableString(String string) {
        myString = string;
    }

    public String getString() {
        return myString;
    }

    @Override
    public int compareTo(ReverseComparableString other) {
        return other.myString.compareTo(myString);
    }

    @Override
    public String toString() {
        return myString;
    }
}

package com.intellij.platform.lsp.impl.features.hierarchy;

import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SymbolTag;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public final class Lsp4jHierarchyItem {
    private final String myName;
    private final @Nullable String myDetail;
    private final SymbolKind myKind;
    private final @Nullable List<SymbolTag> myTags;
    private final String myUri;
    private final Range myRange;
    private final Range mySelectionRange;
    private final @Nullable Object myData;
    private final Object myOriginalItem;

    public Lsp4jHierarchyItem(
        String name,
        @Nullable String detail,
        SymbolKind kind,
        @Nullable List<SymbolTag> tags,
        String uri,
        Range range,
        Range selectionRange,
        @Nullable Object data,
        Object originalItem
    ) {
        myName = name;
        myDetail = detail;
        myKind = kind;
        myTags = tags;
        myUri = uri;
        myRange = range;
        mySelectionRange = selectionRange;
        myData = data;
        myOriginalItem = originalItem;
    }

    public String getName() {
        return myName;
    }

    public @Nullable String getDetail() {
        return myDetail;
    }

    public SymbolKind getKind() {
        return myKind;
    }

    public @Nullable List<SymbolTag> getTags() {
        return myTags;
    }

    public String getUri() {
        return myUri;
    }

    public Range getRange() {
        return myRange;
    }

    public Range getSelectionRange() {
        return mySelectionRange;
    }

    public @Nullable Object getData() {
        return myData;
    }

    public Object getOriginalItem() {
        return myOriginalItem;
    }

    public @Nullable CallHierarchyItem toCallHierarchyItem() {
        return myOriginalItem instanceof CallHierarchyItem item ? item : null;
    }

    public @Nullable TypeHierarchyItem toTypeHierarchyItem() {
        return myOriginalItem instanceof TypeHierarchyItem item ? item : null;
    }

    public static Lsp4jHierarchyItem from(CallHierarchyItem item) {
        return new Lsp4jHierarchyItem(
            item.getName(),
            item.getDetail(),
            item.getKind(),
            item.getTags(),
            item.getUri(),
            item.getRange(),
            item.getSelectionRange(),
            item.getData(),
            item
        );
    }

    public static Lsp4jHierarchyItem from(TypeHierarchyItem item) {
        return new Lsp4jHierarchyItem(
            item.getName(),
            item.getDetail(),
            item.getKind(),
            item.getTags(),
            item.getUri(),
            item.getRange(),
            item.getSelectionRange(),
            item.getData(),
            item
        );
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Lsp4jHierarchyItem that)) {
            return false;
        }
        return myName.equals(that.myName)
            && Objects.equals(myDetail, that.myDetail)
            && myKind == that.myKind
            && Objects.equals(myTags, that.myTags)
            && myUri.equals(that.myUri)
            && myRange.equals(that.myRange)
            && mySelectionRange.equals(that.mySelectionRange)
            && Objects.equals(myData, that.myData)
            && myOriginalItem.equals(that.myOriginalItem);
    }

    @Override
    public int hashCode() {
        return Objects.hash(myName, myDetail, myKind, myTags, myUri, myRange, mySelectionRange, myData, myOriginalItem);
    }

    @Override
    public String toString() {
        return "Lsp4jHierarchyItem(name=" + myName +
            ", detail=" + myDetail +
            ", kind=" + myKind +
            ", tags=" + myTags +
            ", uri=" + myUri +
            ", range=" + myRange +
            ", selectionRange=" + mySelectionRange +
            ", data=" + myData +
            ", originalItem=" + myOriginalItem + ")";
    }
}

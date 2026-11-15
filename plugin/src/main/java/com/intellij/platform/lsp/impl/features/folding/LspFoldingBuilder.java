package com.intellij.platform.lsp.impl.features.folding;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspCachedHighlighting;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.dumb.DumbAware;
import consulo.document.Document;
import consulo.document.util.TextRange;
import consulo.language.Language;
import consulo.language.ast.ASTNode;
import consulo.language.editor.folding.CodeFoldingSettings;
import consulo.language.editor.folding.FoldingBuilderEx;
import consulo.language.editor.folding.FoldingDescriptor;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeKind;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Consulo: registered for {@link Language#ANY}. The descriptors depend on the
 * {@link LspFoldingRangeCache#getModificationTracker() modification tracker} of the cache, so that Consulo's cached folding
 * result is recomputed when a new server response arrives without a PSI change.
 */
@ExtensionImpl
public class LspFoldingBuilder extends FoldingBuilderEx implements DumbAware {
    @Override
    public Language getLanguage() {
        return Language.ANY;
    }

    @Override
    @RequiredReadAction
    public FoldingDescriptor[] buildFoldRegions(PsiElement root, Document document, boolean quick) {
        // LspFoldingBuilder.buildFoldRegions can be called as part of nested CompositeFoldingBuilder
        // With LSP, we're only interested in top-level real files
        if (!(root instanceof PsiFile psiFile)) {
            return FoldingDescriptor.EMPTY;
        }

        VirtualFile file = psiFile.getVirtualFile();
        if (file == null) {
            return FoldingDescriptor.EMPTY;
        }
        if (file instanceof VirtualFileWindow) {
            return FoldingDescriptor.EMPTY;
        }

        List<LspCachedHighlighting<FoldingRange>> foldingRangeInfos = new ArrayList<>();
        Set<Object> dependencies = new HashSet<>();
        for (LspClientImpl client : LspClientManagerImpl.getInstanceImpl(psiFile.getProject()).getClientsWithThisFileOpen(file)) {
            LspFoldingRangeCache cache = client.getHighlightingCacheRegistry().getFoldingRangeCache();
            foldingRangeInfos.addAll(cache.getHighlightings(file));
            dependencies.add(cache.getModificationTracker());
        }

        if (foldingRangeInfos.isEmpty()) {
            return FoldingDescriptor.EMPTY;
        }

        CodeFoldingSettings settings = CodeFoldingSettings.getInstance();
        ASTNode node = psiFile.getNode();
        List<FoldingDescriptor> result = new ArrayList<>(foldingRangeInfos.size());
        for (LspCachedHighlighting<FoldingRange> info : foldingRangeInfos) {
            if (info.getTextRange().isEmpty()) {
                continue;
            }

            String kind = info.getHighlightingInfo().getKind();
            Boolean collapsedByDefault;
            if (FoldingRangeKind.Imports.equals(kind)) {
                collapsedByDefault = settings.COLLAPSE_IMPORTS;
            }
            else if (FoldingRangeKind.Region.equals(kind)) {
                // Consulo: no `COLLAPSE_CUSTOM_FOLDING_REGIONS` setting (IntelliJ default is `false`), so not collapsed by default
                collapsedByDefault = null;
            }
            else if (FoldingRangeKind.Comment.equals(kind)) {
                collapsedByDefault = null; // There's LSP/IJ semantic mismatch: we have `settings.COLLAPSE_DOC_COMMENTS`
            }
            else {
                collapsedByDefault = null;
            }

            result.add(new LspFoldingDescriptor(
                node,
                info.getTextRange(), // always range in file
                info.getHighlightingInfo().getCollapsedText(),
                collapsedByDefault,
                dependencies
            ));
        }
        return result.toArray(FoldingDescriptor.EMPTY);
    }

    @Override
    @RequiredReadAction
    public boolean isCollapsedByDefault(ASTNode node) {
        return false;
    }

    /**
     * May be called by {@code consulo.language.editor.internal.CompositeFoldingBuilder.FoldingDescriptorWrapper.calcPlaceholderText}
     * because {@code null} value is ambiguous, so we just return {@code null} again
     */
    @Override
    @RequiredReadAction
    public @Nullable String getPlaceholderText(ASTNode node, TextRange range) {
        return null;
    }

    @Override
    @RequiredReadAction
    public @Nullable String getPlaceholderText(ASTNode node) {
        throw new UnsupportedOperationException();
    }

    private static final class LspFoldingDescriptor extends FoldingDescriptor {
        private final @Nullable String myCollapsedText;

        private LspFoldingDescriptor(
            ASTNode node,
            TextRange textRange,
            @Nullable String collapsedText,
            @Nullable Boolean collapsedByDefault,
            Set<Object> dependencies
        ) {
            super(node, textRange, null, dependencies, false, collapsedText, collapsedByDefault);
            myCollapsedText = collapsedText;
        }

        /**
         * Override avoids calling {@code consulo.language.editor.internal.CompositeFoldingBuilder.getPlaceholderText}
         */
        @Override
        public @Nullable String getPlaceholderText() {
            return myCollapsedText;
        }

        @Override
        public void setPlaceholderText(@Nullable String placeholderText) {
        }
    }
}

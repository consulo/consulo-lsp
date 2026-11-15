// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.lineMarkers;

import com.intellij.platform.lsp.api.customization.LspInheritanceMarker;
import com.intellij.platform.lsp.api.customization.LspInheritanceMarkersSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspCachedHighlighting;
import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.dumb.DumbAware;
import consulo.application.progress.ProgressManager;
import consulo.codeEditor.markup.GutterIconRenderer;
import consulo.document.util.TextRange;
import consulo.language.Language;
import consulo.language.editor.Pass;
import consulo.language.editor.gutter.LineMarkerInfo;
import consulo.language.editor.gutter.LineMarkerProviderDescriptor;
import consulo.language.editor.gutter.NavigateAction;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.localize.LocalizeValue;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.ui.ex.action.IdeActions;
import consulo.ui.image.Image;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shows the override/implement gutter icons for LSP-backed files.
 * <p>
 * The provider only reads {@link LspInheritanceMarkersCache} snapshots.
 * The cache pulls the data from the server asynchronously and restarts the daemon when a response arrives.
 * <p>
 * Consulo: upstream registers it with {@code language=""}; here {@link #getLanguage()} returns {@link Language#ANY}
 * (the {@code LineMarkerProvider} cache includes {@link Language#ANY} providers).
 */
@ExtensionImpl
public class LspInheritanceLineMarkerProvider extends LineMarkerProviderDescriptor implements DumbAware {
    @Override
    public Language getLanguage() {
        return Language.ANY;
    }

    @Override
    public LocalizeValue getName() {
        return LspLocalize.linemarkersLspinheritancelinemarkerproviderName();
    }

    @Override
    public Image getIcon() {
        return PlatformIconGroup.gutterImplementedmethod();
    }

    @Override
    @RequiredReadAction
    public @Nullable LineMarkerInfo getLineMarkerInfo(PsiElement element) {
        return null;
    }

    @Override
    @RequiredReadAction
    @SuppressWarnings({"rawtypes", "deprecation"})
    public void collectSlowLineMarkers(List<PsiElement> elements, Collection<LineMarkerInfo> result) {
        if (elements.isEmpty()) {
            return;
        }
        PsiFile psiFile = elements.get(0).getContainingFile();
        if (psiFile == null) {
            return;
        }
        VirtualFile virtualFile = psiFile.getVirtualFile();
        if (virtualFile == null) {
            return;
        }
        if (virtualFile instanceof VirtualFileWindow) {
            return;
        }

        // computed lazily: most files have no LSP client with this feature
        Set<PsiElement> elementSet = null;

        for (LspClientImpl client : LspClientManagerImpl.getInstanceImpl(psiFile.getProject()).getClientsWithThisFileOpen(virtualFile)) {
            if (!(client.getDescriptor().getLspCustomization().getInheritanceMarkersCustomizer() instanceof LspInheritanceMarkersSupport)) {
                continue;
            }
            for (LspCachedHighlighting<LspInheritanceMarker> cached :
                client.getHighlightingCacheRegistry().getInheritanceMarkersCache().getHighlightings(virtualFile)) {
                ProgressManager.checkCanceled();
                LspInheritanceMarker marker = cached.getHighlightingInfo();

                // A snapshot range can lag behind the document for a moment, and LineMarkerInfo rejects an out-of-file range.
                TextRange anchorRange = cached.getTextRange().intersection(psiFile.getTextRange());
                if (anchorRange == null) {
                    continue;
                }
                PsiElement anchor = psiFile.findElementAt(anchorRange.getStartOffset());
                if (anchor == null) {
                    continue;
                }
                if (elementSet == null) {
                    elementSet = new HashSet<>(elements);
                }
                // The contract of collectSlowLineMarkers: create markers only for the passed elements.
                if (!elementSet.contains(anchor)) {
                    continue;
                }

                result.add(createLineMarkerInfo(client, virtualFile, anchor, anchorRange, marker));
            }
        }
    }

    private LineMarkerInfo<PsiElement> createLineMarkerInfo(
        LspClientImpl lspClient,
        VirtualFile file,
        PsiElement anchor,
        TextRange anchorRange,
        LspInheritanceMarker marker
    ) {
        Image icon = switch (marker.getKind()) {
            case IMPLEMENTED_METHOD, IMPLEMENTED_CLASS -> PlatformIconGroup.gutterImplementedmethod();
            case OVERRIDDEN_METHOD, SUBCLASSED_CLASS -> PlatformIconGroup.gutterOverridenmethod();
        };
        String tooltip = tooltip(marker).get();
        // Consulo: LineMarkerInfo has no accessible name provider (upstream passes the tooltip again)
        LineMarkerInfo<PsiElement> info = new LineMarkerInfo<>(
            anchor,
            anchorRange,
            icon,
            Pass.LINE_MARKERS,
            it -> tooltip,
            new LspInheritanceMarkerNavigationHandler(lspClient, file, anchorRange.getStartOffset(), marker),
            GutterIconRenderer.Alignment.RIGHT
        );
        return NavigateAction.setNavigateAction(info, tooltip, IdeActions.ACTION_GOTO_IMPLEMENTATION);
    }

    private static LocalizeValue tooltip(LspInheritanceMarker marker) {
        return switch (marker.getKind()) {
            case IMPLEMENTED_METHOD, IMPLEMENTED_CLASS -> LspLocalize.linemarkersTooltipHasImplementations();
            case OVERRIDDEN_METHOD -> LspLocalize.linemarkersTooltipIsOverridden();
            case SUBCLASSED_CLASS -> LspLocalize.linemarkersTooltipHasSubtypes();
        };
    }
}

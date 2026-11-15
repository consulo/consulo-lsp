package com.intellij.platform.lsp.impl.features.highlightingCommon;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.features.codeLens.LspCodeLensCache;
import com.intellij.platform.lsp.impl.features.folding.LspFoldingRangeCache;
import com.intellij.platform.lsp.impl.features.highlighting.DiagnosticAndQuickFixes;
import com.intellij.platform.lsp.impl.features.highlighting.LspDiagnosticAndLazyQuickFixes;
import com.intellij.platform.lsp.impl.features.highlighting.LspDocumentLinkCache;
import com.intellij.platform.lsp.impl.features.highlighting.LspPublishDiagnosticsCache;
import com.intellij.platform.lsp.impl.features.highlighting.LspPullDiagnosticsCache;
import com.intellij.platform.lsp.impl.features.highlighting.LspSemanticTokensCache;
import com.intellij.platform.lsp.impl.features.inlayHint.LspInlayHintsCache;
import com.intellij.platform.lsp.impl.features.inlayHintColor.LspDocumentColorCache;
import com.intellij.platform.lsp.impl.features.lineMarkers.LspInheritanceMarkersCache;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.document.event.DocumentEvent;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Diagnostic;

import java.util.ArrayList;
import java.util.List;

public class LspHighlightingCacheRegistry {
    private final LspClientImpl myLspClient;
    private final List<LspHighlightingCache<?>> myAllCaches = new ArrayList<>();

    private final LspPublishDiagnosticsCache myPublishDiagnosticsCache;
    private final LspPullDiagnosticsCache myPullDiagnosticsCache;
    private final LspSemanticTokensCache mySemanticTokensCache;
    private final LspDocumentLinkCache myDocumentLinkCache;

    private final LspInlayHintsCache myInlayHintsCache;
    private final LspDocumentColorCache myDocumentColorCache;
    private final LspFoldingRangeCache myFoldingRangeCache;
    private final LspCodeLensCache myCodeLensCache;
    private final LspInheritanceMarkersCache myInheritanceMarkersCache;

    public LspHighlightingCacheRegistry(LspClientImpl lspClient) {
        myLspClient = lspClient;
        myPublishDiagnosticsCache = register(new LspPublishDiagnosticsCache(lspClient));
        myPullDiagnosticsCache = register(new LspPullDiagnosticsCache(lspClient));
        mySemanticTokensCache = register(new LspSemanticTokensCache(lspClient));
        myDocumentLinkCache = register(new LspDocumentLinkCache(lspClient));

        myInlayHintsCache = register(new LspInlayHintsCache(lspClient));
        myDocumentColorCache = register(new LspDocumentColorCache(lspClient));
        myFoldingRangeCache = register(new LspFoldingRangeCache(lspClient));
        myCodeLensCache = register(new LspCodeLensCache(lspClient));
        myInheritanceMarkersCache = register(new LspInheritanceMarkersCache(lspClient));
    }

    public LspPublishDiagnosticsCache getPublishDiagnosticsCache() {
        return myPublishDiagnosticsCache;
    }

    public LspPullDiagnosticsCache getPullDiagnosticsCache() {
        return myPullDiagnosticsCache;
    }

    public LspSemanticTokensCache getSemanticTokensCache() {
        return mySemanticTokensCache;
    }

    public LspDocumentLinkCache getDocumentLinkCache() {
        return myDocumentLinkCache;
    }

    public LspInlayHintsCache getInlayHintsCache() {
        return myInlayHintsCache;
    }

    public LspDocumentColorCache getDocumentColorCache() {
        return myDocumentColorCache;
    }

    public LspFoldingRangeCache getFoldingRangeCache() {
        return myFoldingRangeCache;
    }

    public LspCodeLensCache getCodeLensCache() {
        return myCodeLensCache;
    }

    public LspInheritanceMarkersCache getInheritanceMarkersCache() {
        return myInheritanceMarkersCache;
    }

    private <T extends LspHighlightingCache<?>> T register(T cache) {
        myAllCaches.add(cache);
        return cache;
    }

    public void fileEdited(VirtualFile file, DocumentEvent e) {
        for (LspHighlightingCache<?> cache : myAllCaches) {
            cache.fileEdited(file, e);
        }
    }

    public void clearCache() {
        for (LspHighlightingCache<?> cache : myAllCaches) {
            cache.clearCache();
        }
    }

    /**
     * Forgets every result the IDE pulled for {@code file} once the client has closed it, so a reopened file is pulled again.
     * Pushed diagnostics are left alone: the server owns those and clears them itself.
     */
    public void fileClosed(VirtualFile file) {
        for (LspHighlightingCache<?> cache : myAllCaches) {
            if (cache.getSupportsPull()) {
                cache.fileClosed(file);
            }
        }
    }

    /**
     * Marks every result the IDE pulled for {@code file} stale, so the next read re-requests it while the current results
     * stay on screen. Pushed diagnostics are left alone: the server resends those itself.
     */
    public void invalidatePulledResults(VirtualFile file) {
        for (LspHighlightingCache<?> cache : myAllCaches) {
            if (cache.getSupportsPull()) {
                cache.forceFullRepull(file);
            }
        }
    }

    /**
     * The quick fixes in the returned list are special wrappers that may or may not possess a real
     * {@link com.intellij.platform.lsp.api.customization.LspIntentionAction LspIntentionAction} object at this moment,
     * but clients shouldn't care about that. For curious: see
     * {@link com.intellij.platform.lsp.impl.features.quickFix.LspQuickFixSet LspQuickFixSet}.
     * <p>
     * Must be called from a background thread.
     */
    public List<DiagnosticAndQuickFixes> getDiagnosticsAndQuickFixes(VirtualFile file) {
        List<LspCachedHighlighting<LspDiagnosticAndLazyQuickFixes>> pushedCachedHighlightings =
            myPublishDiagnosticsCache.getHighlightings(file);
        List<Diagnostic> pushedDiagnostics = new ArrayList<>(pushedCachedHighlightings.size());
        for (LspCachedHighlighting<LspDiagnosticAndLazyQuickFixes> it : pushedCachedHighlightings) {
            pushedDiagnostics.add(it.getHighlightingInfo().getDiagnostic());
        }

        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            return List.of();
        }

        List<DiagnosticAndQuickFixes> result = new ArrayList<>();
        for (LspCachedHighlighting<LspDiagnosticAndLazyQuickFixes> it : pushedCachedHighlightings) {
            Diagnostic updatedDiagnostic = LspDiagnosticAndLazyQuickFixes.copyDiagnosticWithRange(
                it.getHighlightingInfo().getDiagnostic(),
                Lsp4jUtilKt.getLsp4jRange(document, it.getTextRange().getStartOffset(), it.getTextRange().getLength())
            );
            result.add(new DiagnosticAndQuickFixes(updatedDiagnostic, it.getHighlightingInfo().getQuickFixes(myLspClient, file)));
        }

        result.addAll(getPulledDiagnosticsAndQuickFixes(file, document, pushedDiagnostics));
        return result;
    }

    private List<DiagnosticAndQuickFixes> getPulledDiagnosticsAndQuickFixes(
        VirtualFile file,
        Document document,
        List<Diagnostic> alreadyKnownDiagnostics
    ) {
        List<LspCachedHighlighting<LspDiagnosticAndLazyQuickFixes>> pulledDiagnosticsAndLazyQuickFixes =
            myPullDiagnosticsCache.getHighlightings(file);
        if (pulledDiagnosticsAndLazyQuickFixes.isEmpty()) {
            return List.of();
        }

        List<DiagnosticAndQuickFixes> result = new ArrayList<>();
        for (LspCachedHighlighting<LspDiagnosticAndLazyQuickFixes> it : pulledDiagnosticsAndLazyQuickFixes) {
            Diagnostic diagnostic = it.getHighlightingInfo().getDiagnostic();
            if (alreadyKnownDiagnostics.contains(diagnostic)) {
                continue;
            }

            Diagnostic updatedDiagnostic = LspDiagnosticAndLazyQuickFixes.copyDiagnosticWithRange(
                diagnostic,
                Lsp4jUtilKt.getLsp4jRange(document, it.getTextRange().getStartOffset(), it.getTextRange().getLength())
            );
            result.add(new DiagnosticAndQuickFixes(updatedDiagnostic, it.getHighlightingInfo().getQuickFixes(myLspClient, file)));
        }
        return result;
    }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.highlighting;

import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport;
import com.intellij.platform.lsp.api.customization.LspDocumentLinkDisabled;
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport;
import com.intellij.platform.lsp.api.customization.LspCustomization;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import consulo.annotation.access.RequiredReadAction;
import consulo.application.dumb.DumbAware;
import consulo.application.progress.ProgressIndicator;
import consulo.codeEditor.Editor;
import consulo.document.Document;
import consulo.language.editor.highlight.TextEditorHighlightingPass;
import consulo.language.editor.highlight.UpdateHighlightersUtil;
import consulo.language.editor.rawHighlight.HighlightInfo;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * A highlighting pass that applies LSP highlighting (diagnostics, semantic tokens, document links) to the editor.
 * <p>
 * This pass has two roles:
 * <ol>
 * <li><b>Trigger pull-based caches</b>: Calling {@code getHighlightings()}
 * on pull-based caches checks the document-stamp staleness and triggers {@code scheduleHighlightingsUpdate()} when stale,
 * which sends pull requests to the server. Without the pass, pull diagnostics would never refresh after edits.</li>
 * <li><b>Apply highlights</b>: Converts cache data to {@link HighlightInfo} and applies to the editor.
 * This handles edge cases like the initial file open before the server responds. This may duplicate
 * work already done by the reactive path ({@link LspHighlightingApplier}), but ensures correctness after document edits.</li>
 * </ol>
 * The infos collected in {@link #doCollectInformation}
 * are applied with {@link UpdateHighlightersUtil} in {@link #doApplyInformationToEditor}.
 */
public class LspHighlightingPass extends TextEditorHighlightingPass implements DumbAware {
    private final Document myHostDocument;
    private final PsiFile myPsiFile;
    private final VirtualFile myFile;
    private final @Nullable Editor myEditor;

    private List<HighlightInfo> myHighlightInfos = List.of();
    private int myGroupId = LspHighlightingApplier.UNREGISTERED_PASS_ID;

    public LspHighlightingPass(Project project, Document document, PsiFile psiFile, VirtualFile file, @Nullable Editor editor) {
        super(project, document, false);
        myHostDocument = document;
        myPsiFile = psiFile;
        myFile = file;
        myEditor = editor;
    }

    @Override
    @RequiredReadAction
    public void doCollectInformation(ProgressIndicator progress) {
        if (myFile instanceof VirtualFileWindow) {
            return;
        }

        // GROUP_ID is assigned by the platform during pass registration; skip if not yet initialized.
        // The platform will re-run the pass once registration completes.
        int groupId = LspHighlightingApplier.GROUP_ID;
        if (groupId == LspHighlightingApplier.UNREGISTERED_PASS_ID) {
            return;
        }

        // clients may be empty
        Collection<LspClientImpl> clients = LspClientManagerImpl.getInstanceImpl(myProject).getClientsWithThisFileOpen(myFile);

        // Always trigger pull-based caches (semantic tokens, pull diagnostics, document links).
        // This is critical: getHighlightings() checks the
        // document stamp and schedules server requests when stale.
        for (LspClientImpl client : clients) {
            LspCustomization customization = client.getDescriptor().getLspCustomization();
            if (customization.getDiagnosticsCustomizer() instanceof LspDiagnosticsSupport) {
                client.getHighlightingCacheRegistry().getDiagnosticsAndQuickFixes(myFile); // triggers pull diagnostics cache
            }

            if (customization.getSemanticTokensCustomizer() instanceof LspSemanticTokensSupport) {
                client.getHighlightingCacheRegistry().getSemanticTokensCache().getHighlightings(myFile); // triggers semantic tokens cache
            }

            if (!(customization.getDocumentLinkCustomizer() instanceof LspDocumentLinkDisabled)) {
                client.getHighlightingCacheRegistry().getDocumentLinkCache().getHighlightings(myFile); // triggers document link cache
            }
        }

        LspHighlightingApplier applier = LspHighlightingApplier.getInstance(myProject);

        // Snapshot the cache generation *before* reading the cache, so that if a reactive refresh
        // writes Wolf with a newer snapshot in between, our reportErrorsToWolf below is dropped as stale.
        long observedGen = applier.currentGeneration(myFile);
        List<HighlightInfo> highlights = applier.collectHighlightInfos(myPsiFile, myFile, myHostDocument);
        myHighlightInfos = highlights;
        myGroupId = groupId;

        applier.reportErrorsToWolf(myFile, highlights, observedGen);
    }

    @Override
    @RequiredUIAccess
    public void doApplyInformationToEditor() {
        if (myGroupId != LspHighlightingApplier.UNREGISTERED_PASS_ID) {
            UpdateHighlightersUtil.setHighlightersToEditor(
                myProject, myHostDocument, 0, myHostDocument.getTextLength(), myHighlightInfos, getColorsScheme(), myGroupId
            );
        }
        // Save PSI modification stamp so the factory can skip the next pass if PSI hasn't changed.
        if (myEditor != null) {
            myEditor.putUserData(
                LspHighlightingPassFactory.PSI_MODIFICATION_STAMP,
                PsiManager.getInstance(myProject).getModificationTracker().getModificationCount()
            );
        }
    }

    /**
     * Called by the daemon to collect results for batch analysis
     */
    @Override
    public List<HighlightInfo> getInfos() {
        return myHighlightInfos;
    }
}

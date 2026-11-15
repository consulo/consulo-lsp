// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.highlighting;

import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.dumb.DumbAware;
import consulo.codeEditor.Editor;
import consulo.document.Document;
import consulo.language.editor.Pass;
import consulo.language.editor.highlight.TextEditorHighlightingPass;
import consulo.language.editor.impl.highlight.HighlightInfoProcessor;
import consulo.language.editor.impl.highlight.MainHighlightingPassFactory;
import consulo.language.psi.PsiFile;
import consulo.util.dataholder.Key;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

/**
 * Consulo: IntelliJ's {@code TextEditorHighlightingPassFactoryRegistrar.registerHighlightingPassFactory} is
 * {@link #register(Registrar)} of the {@code TextEditorHighlightingPassFactory} extension.
 */
@ExtensionImpl
public class LspHighlightingPassFactory implements MainHighlightingPassFactory, DumbAware {
    public static final Key<Long> PSI_MODIFICATION_STAMP = Key.create("lsp.highlighting.psi.modification.stamp");

    @Override
    public void register(Registrar registrar) {
        LspHighlightingApplier.GROUP_ID = registrar.registerTextEditorHighlightingPass(
            this,
            null, // runAfterCompletionOf
            new int[]{Pass.UPDATE_ALL}, // runAfterStartingOf
            false, // runIntentionsPassAfter
            -1 // forcedPassId
        );
    }

    @Override
    @RequiredReadAction
    public @Nullable TextEditorHighlightingPass createHighlightingPass(PsiFile psiFile, Editor editor) {
        VirtualFile file = psiFile.getVirtualFile();
        if (file == null) {
            return null;
        }

        long currentStamp = psiFile.getManager().getModificationTracker().getModificationCount();
        Long stamp = editor.getUserData(PSI_MODIFICATION_STAMP);
        if (stamp != null && stamp == currentStamp) {
            return null;
        }

        return new LspHighlightingPass(psiFile.getProject(), editor.getDocument(), psiFile, file, editor);
    }

    @Override
    @RequiredReadAction
    public @Nullable TextEditorHighlightingPass createMainHighlightingPass(
        PsiFile psiFile,
        Document document,
        HighlightInfoProcessor highlightInfoProcessor
    ) {
        // For read-only / batch analysis (e.g., "Inspect Code")
        VirtualFile file = psiFile.getVirtualFile();
        if (file == null) {
            return null;
        }
        return new LspHighlightingPass(psiFile.getProject(), document, psiFile, file, null);
    }
}

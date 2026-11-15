// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.highlighting;

import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import consulo.annotation.component.ExtensionImpl;
import consulo.language.editor.wolfAnalyzer.WolfFileProblemFilter;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.inject.Inject;

/**
 * Allows {@link consulo.language.editor.wolfAnalyzer.WolfTheProblemSolver} to accept error reports for files
 * that have an active LSP server open. Without this filter, Wolf silently ignores
 * {@link consulo.language.editor.wolfAnalyzer.WolfTheProblemSolver#reportProblems} calls.
 * <p>
 * Consulo: IntelliJ's {@code problemFileHighlightFilter} ({@code Condition<VirtualFile>}) is {@link WolfFileProblemFilter}.
 */
@ExtensionImpl
public class LspProblemFileHighlightFilter implements WolfFileProblemFilter {
    private final Project myProject;

    @Inject
    public LspProblemFileHighlightFilter(Project project) {
        myProject = project;
    }

    @Override
    public boolean isToBeHighlighted(VirtualFile file) {
        return !LspClientManagerImpl.getInstanceImpl(myProject).getClientsWithThisFileOpen(file).isEmpty();
    }
}

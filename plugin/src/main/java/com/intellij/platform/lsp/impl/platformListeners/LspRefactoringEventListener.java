// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.platformListeners;

import com.intellij.platform.lsp.impl.features.workspaceFileOperations.LspWillRenameFilesHandler;
import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.TopicImpl;
import consulo.language.editor.refactoring.event.RefactoringEventData;
import consulo.language.editor.refactoring.event.RefactoringEventListener;
import consulo.project.Project;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

/**
 * Upstream: {@code <projectListeners><listener topic="com.intellij.refactoring.listeners.RefactoringEventListener"/>}.
 */
@TopicImpl(ComponentScope.PROJECT)
public class LspRefactoringEventListener implements RefactoringEventListener {
    /**
     * The ID of the refactoring in {@code RenameProcessor}.
     */
    public static final String RENAME_REFACTORING_ID = "refactoring.rename";

    /**
     * The ID of the refactoring in {@code MoveFilesOrDirectoriesProcessor}.
     */
    public static final String MOVE_REFACTORING_ID = "refactoring.move";

    private final Project myProject;

    @Inject
    public LspRefactoringEventListener(Project project) {
        myProject = project;
    }

    @Override
    public void refactoringStarted(String refactoringId, @Nullable RefactoringEventData beforeData) {
        if (beforeData == null) {
            return;
        }
        if (refactoringId.equals(RENAME_REFACTORING_ID) || refactoringId.equals(MOVE_REFACTORING_ID)) {
            LspWillRenameFilesHandler.renameOrMoveRefactoringStarted(myProject, refactoringId, beforeData);
        }
    }

    @Override
    public void refactoringDone(String refactoringId, @Nullable RefactoringEventData afterData) {
    }

    @Override
    public void conflictsDetected(String refactoringId, RefactoringEventData conflictsData) {
    }

    @Override
    public void undoRefactoring(String refactoringId) {
    }
}

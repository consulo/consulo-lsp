// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.navigation;

import com.intellij.platform.lsp.api.customization.LspGoToImplementationSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.language.pom.PomDeclarationSearcher;
import consulo.language.pom.PomTarget;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.lsp.impl.compat.ActionContextElement;
import consulo.project.Project;
import consulo.ui.ex.action.IdeActions;
import consulo.virtualFileSystem.VirtualFile;

import java.util.function.Consumer;

/**
 * The first half of the Go To Implementation feature for LSP-backed files.
 * <p>
 * {@code GotoImplementationHandler} needs a non-null source element from {@code TargetElementUtil.findTargetElement()}.
 * LSP-backed files usually have flat PSI without named elements or references, so the standard lookup finds nothing.
 * This searcher runs inside {@code TargetElementUtil.getNamedElement()} and provides the source element:
 * a {@link LspGotoImplementationTarget} that keeps the file and the caret offset.
 * {@link LspDefinitionsScopedSearcher} then recognizes this target and asks the LSP server for the implementations.
 * <p>
 * A {@code PomDeclarationSearcher} is used instead of a {@code TargetElementEvaluatorEx2} on purpose:
 * all {@code PomDeclarationSearcher} extensions are iterated,
 * while only one {@code TargetElementEvaluatorEx2} per language is ever consulted (see IJPL-189562).
 * <p>
 * The action thread context is {@link ActionContextElement} (see there).
 */
@ExtensionImpl
public class LspImplementationDeclarationSearcher extends PomDeclarationSearcher {
    @Override
    @RequiredReadAction
    public void findDeclarationsAt(PsiElement element, int offsetInElement, Consumer<PomTarget> consumer) {
        // This function is called for many features that need a named element at the offset.
        // We care only about the "Go To Implementation" action, which the thread context names.
        ActionContextElement context = ActionContextElement.getActionThreadContext();
        if (context == null || !IdeActions.ACTION_GOTO_IMPLEMENTATION.equals(context.getActionId())) {
            return;
        }

        PsiFile psiFile = element.getContainingFile();
        if (psiFile == null) {
            return;
        }
        Project project = psiFile.getProject();
        if (project.isDefault()) {
            return;
        }
        VirtualFile file = psiFile.getVirtualFile();
        if (file == null) {
            return;
        }
        if (file instanceof VirtualFileWindow) {
            return;
        }

        boolean hasSupportingClient = false;
        for (LspClientImpl client : LspClientManagerImpl.getInstanceImpl(project).getClientsForFileRequests(file)) {
            if (LspServerCapabilitiesKt.supportsGotoImplementation(client, file) &&
                client.getDescriptor().getLspCustomization().getGoToImplementationCustomizer() instanceof LspGoToImplementationSupport) {
                hasSupportingClient = true;
                break;
            }
        }
        if (!hasSupportingClient) {
            return;
        }

        consumer.accept(new LspGotoImplementationTarget(psiFile, element.getTextRange().getStartOffset() + offsetInElement));
    }
}

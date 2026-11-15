package com.intellij.platform.lsp.impl.features.extract;

import consulo.annotation.component.ExtensionImpl;
import consulo.language.editor.refactoring.action.RefactoringActionHandler;

import java.util.List;

/**
 * Upstream: the nested {@code LspRefactoringSupportProvider.IntroduceField}. Consulo: a top-level class, because the Consulo annotation
 * processor does not generate bindings for nested extension classes yet.
 */
@ExtensionImpl
public final class LspRefactoringSupportProviderIntroduceField extends LspRefactoringSupportProvider {
    public LspRefactoringSupportProviderIntroduceField() {
        super(List.of("refactor.extract.field", "refactor.extract.constant"));
    }

    @Override
    public RefactoringActionHandler getIntroduceFieldHandler() {
        return new LspExtractRefactoringHandler(myKinds);
    }
}

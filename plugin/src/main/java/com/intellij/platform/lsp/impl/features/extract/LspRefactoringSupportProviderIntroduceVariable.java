package com.intellij.platform.lsp.impl.features.extract;

import consulo.annotation.component.ExtensionImpl;
import consulo.language.editor.refactoring.action.RefactoringActionHandler;

import java.util.List;

/**
 * A top-level class, because the annotation processor does not generate bindings for nested extension classes yet.
 */
@ExtensionImpl
public final class LspRefactoringSupportProviderIntroduceVariable extends LspRefactoringSupportProvider {
    public LspRefactoringSupportProviderIntroduceVariable() {
        super(List.of("refactor.extract.variable", "refactor.extract.constant"));
    }

    @Override
    public RefactoringActionHandler getIntroduceVariableHandler() {
        return new LspExtractRefactoringHandler(myKinds);
    }
}

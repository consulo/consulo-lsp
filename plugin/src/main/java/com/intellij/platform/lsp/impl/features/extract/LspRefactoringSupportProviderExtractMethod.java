package com.intellij.platform.lsp.impl.features.extract;

import consulo.annotation.component.ExtensionImpl;
import consulo.language.editor.refactoring.action.RefactoringActionHandler;

import java.util.List;

/**
 * Upstream: the nested {@code LspRefactoringSupportProvider.ExtractMethod}. Consulo: a top-level class, because the Consulo annotation
 * processor does not generate bindings for nested extension classes yet.
 */
@ExtensionImpl
public final class LspRefactoringSupportProviderExtractMethod extends LspRefactoringSupportProvider {
    public LspRefactoringSupportProviderExtractMethod() {
        super(List.of("refactor.extract.function"));
    }

    @Override
    public RefactoringActionHandler getExtractMethodHandler() {
        return new LspExtractRefactoringHandler(myKinds);
    }
}

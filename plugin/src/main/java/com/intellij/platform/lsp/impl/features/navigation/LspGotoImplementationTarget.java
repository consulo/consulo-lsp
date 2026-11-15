// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.navigation;

import consulo.language.pom.PomNamedTarget;
import consulo.language.psi.PsiFile;
import consulo.navigation.OpenFileDescriptorFactory;
import consulo.virtualFileSystem.VirtualFile;

/**
 * The source element for the "Go To Implementation" action in an LSP-backed file.
 * Keeps the caret offset because {@code DefinitionsScopedSearch.SearchParameters} transfers only an element.
 * <p>
 * The target must not implement {@code PsiTarget}.
 * For a {@code PsiTarget}, the platform {@code PomDefinitionSearch} reports the navigation element as an implementation.
 * That self result would win over the LSP results.
 */
public class LspGotoImplementationTarget implements PomNamedTarget {
    private final PsiFile myPsiFile;
    private final int myOffsetInFile;

    public LspGotoImplementationTarget(PsiFile psiFile, int offsetInFile) {
        myPsiFile = psiFile;
        myOffsetInFile = offsetInFile;
    }

    public PsiFile getPsiFile() {
        return myPsiFile;
    }

    public int getOffsetInFile() {
        return myOffsetInFile;
    }

    /**
     * The word at the caret. The "Implementations of ..." popup title shows it.
     */
    @Override
    public String getName() {
        String word = LspGotoImplementationKt.wordAt(myPsiFile, myOffsetInFile);
        return word != null ? word : myPsiFile.getName();
    }

    @Override
    public boolean isValid() {
        return myPsiFile.isValid();
    }

    @Override
    public void navigate(boolean requestFocus) {
        VirtualFile virtualFile = myPsiFile.getVirtualFile();
        if (virtualFile == null) {
            return;
        }
        OpenFileDescriptorFactory.getInstance(myPsiFile.getProject())
            .newBuilder(virtualFile)
            .offset(myOffsetInFile)
            .build()
            .navigate(requestFocus);
    }

    @Override
    public boolean canNavigate() {
        return myPsiFile.getVirtualFile() != null;
    }

    @Override
    public boolean canNavigateToSource() {
        return canNavigate();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LspGotoImplementationTarget that && that.myPsiFile.equals(myPsiFile) && that.myOffsetInFile == myOffsetInFile;
    }

    @Override
    public int hashCode() {
        return 31 * myPsiFile.hashCode() + myOffsetInFile;
    }
}

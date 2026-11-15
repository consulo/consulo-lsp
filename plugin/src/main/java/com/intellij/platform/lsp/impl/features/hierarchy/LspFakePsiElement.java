package com.intellij.platform.lsp.impl.features.hierarchy;

import consulo.annotation.access.RequiredReadAction;
import consulo.language.impl.psi.FakePsiElement;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import org.eclipse.lsp4j.Position;

public class LspFakePsiElement extends FakePsiElement {
    private final PsiFile myPsiFile;
    private final Position myLsp4jPosition;
    private final String myName;

    public LspFakePsiElement(PsiFile psiFile, Position lsp4jPosition) {
        this(psiFile, lsp4jPosition, "");
    }

    public LspFakePsiElement(PsiFile psiFile, Position lsp4jPosition, String name) {
        myPsiFile = psiFile;
        myLsp4jPosition = lsp4jPosition;
        myName = name;
    }

    public PsiFile getPsiFile() {
        return myPsiFile;
    }

    public Position getLsp4jPosition() {
        return myLsp4jPosition;
    }

    @Override
    public PsiElement getParent() {
        return myPsiFile;
    }

    @Override
    @RequiredReadAction
    public PsiFile getContainingFile() {
        return myPsiFile;
    }

    @Override
    @RequiredReadAction
    public String getName() {
        return myName;
    }

    @Override
    public boolean isValid() {
        return true;
    }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.navigation;

import consulo.document.util.TextRange;
import consulo.language.impl.psi.FakePsiElement;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.ui.image.Image;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.VirtualFileManager;
import org.jspecify.annotations.Nullable;

/**
 * An implementation found by the LSP server.
 * A wrapper is needed because LSP-backed files usually have flat PSI:
 * {@code psiFile.findElementAt()} returns the leaf that spans the whole file,
 * so navigation to that leaf would put the caret at the file start.
 * This element keeps the exact target offset, and navigation goes to it via {@link #getTextOffset}.
 */
public class LspImplementationTargetElement extends FakePsiElement {
    private final PsiFile myTargetPsiFile;
    private final int myTargetOffset;

    public LspImplementationTargetElement(PsiFile targetPsiFile, int targetOffset) {
        myTargetPsiFile = targetPsiFile;
        myTargetOffset = targetOffset;
    }

    @Override
    public PsiElement getParent() {
        return myTargetPsiFile;
    }

    @Override
    public PsiFile getContainingFile() {
        return myTargetPsiFile;
    }

    @Override
    public int getTextOffset() {
        return myTargetOffset;
    }

    @Override
    public TextRange getTextRange() {
        return TextRange.from(myTargetOffset, 0);
    }

    @Override
    public boolean isValid() {
        return myTargetPsiFile.isValid();
    }

    @Override
    public String getName() {
        String word = LspGotoImplementationKt.wordAt(myTargetPsiFile, myTargetOffset);
        return word != null ? word : myTargetPsiFile.getName();
    }

    @Override
    public @Nullable String getLocationString() {
        return myTargetPsiFile.getName();
    }

    /**
     * The popup row icon. The LSP response has no symbol kind, so the target file icon is used.
     */
    @Override
    public @Nullable Image getIcon() {
        VirtualFile virtualFile = myTargetPsiFile.getVirtualFile();
        if (virtualFile == null) {
            return null;
        }
        return VirtualFileManager.getInstance().getFileIcon(virtualFile, myTargetPsiFile.getProject(), 0);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LspImplementationTargetElement that &&
            that.myTargetPsiFile.equals(myTargetPsiFile) &&
            that.myTargetOffset == myTargetOffset;
    }

    @Override
    public int hashCode() {
        return 31 * myTargetPsiFile.hashCode() + myTargetOffset;
    }
}

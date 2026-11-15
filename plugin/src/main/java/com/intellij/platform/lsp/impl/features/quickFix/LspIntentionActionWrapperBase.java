package com.intellij.platform.lsp.impl.features.quickFix;

import com.intellij.platform.lsp.api.customization.LspIntentionAction;
import consulo.application.dumb.DumbAware;
import consulo.codeEditor.Editor;
import consulo.language.editor.intention.IntentionAction;
import consulo.language.psi.PsiFile;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import org.eclipse.lsp4j.CodeAction;
import org.jspecify.annotations.Nullable;

/**
 * A base class for the {@code LspQuickFixWrapper} and the {@link com.intellij.platform.lsp.impl.features.intention.LspIntentionActionWrapper}.
 * This class helps to create an {@link IntentionAction} object at the moment when the real behavior of this {@code IntentionAction} is not yet known.
 * The real behavior becomes known later, when the {@link #getLspIntentionAction() lspIntentionAction} property is initialized.
 * {@link LspIntentionAction} object holds the {@link CodeAction} object, which is a response from the LSP server that contains all the information
 * needed to make this class actionable.
 * <p>
 * Consulo: {@code IntentionAction} has no {@code getFamilyName()} and no {@code generatePreview()};
 * {@link #getFamilyName()} is kept as a plain method, the preview is not ported.
 */
public abstract class LspIntentionActionWrapperBase implements IntentionAction, Comparable<IntentionAction>, DumbAware {
    protected final int myIndex;

    protected LspIntentionActionWrapperBase(int index) {
        myIndex = index;
    }

    protected int getIndex() {
        return myIndex;
    }

    public abstract @Nullable LspIntentionAction getLspIntentionAction();

    public abstract void setLspIntentionAction(@Nullable LspIntentionAction lspIntentionAction);

    /**
     * For performance reasons, this function shouldn't send a request to the LSP server
     */
    public LocalizeValue getFamilyName() {
        return LocalizeValue.empty();
    }

    // Some intention actions must be called without a write action (e.g., they only apply Command)
    @Override
    public boolean startInWriteAction() {
        return false;
    }

    // Implementing `Comparable` interface is needed to preserve the order as returned by the LSP server
    @Override
    public int compareTo(IntentionAction other) {
        return other instanceof LspIntentionActionWrapperBase otherWrapper ? myIndex - otherWrapper.myIndex : 0;
    }

    @Override
    public LocalizeValue getText() {
        LspIntentionAction action = getLspIntentionAction();
        return action != null ? action.getText() : LocalizeValue.empty();
    }

    @Override
    public boolean isAvailable(Project project, Editor editor, PsiFile psiFile) {
        LspIntentionAction action = getLspIntentionAction();
        return action != null && action.isAvailable(project, editor, psiFile);
    }

    @Override
    public void invoke(Project project, Editor editor, PsiFile psiFile) {
        LspIntentionAction action = getLspIntentionAction();
        if (action != null) {
            action.invoke(project, editor, psiFile);
        }
    }
}

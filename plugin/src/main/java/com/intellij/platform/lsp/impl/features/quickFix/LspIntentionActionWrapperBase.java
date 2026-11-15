package com.intellij.platform.lsp.impl.features.quickFix;

import com.intellij.platform.lsp.api.customization.LspIntentionAction;
import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.application.Application;
import consulo.application.ReadAction;
import consulo.application.dumb.DumbAware;
import consulo.application.progress.ProgressManager;
import consulo.codeEditor.Editor;
import consulo.component.ProcessCanceledException;
import consulo.language.editor.intention.IntentionAction;
import consulo.language.psi.PsiFile;
import consulo.localize.LocalizeValue;
import consulo.lsp.impl.compat.ActionContextElement;
import consulo.project.Project;
import consulo.util.lang.function.ThrowableSupplier;
import org.eclipse.lsp4j.CodeAction;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/**
 * A base class for the {@code LspQuickFixWrapper} and the {@link com.intellij.platform.lsp.impl.features.intention.LspIntentionActionWrapper}.
 * This class helps to create an {@link IntentionAction} object at the moment when the real behavior of this {@code IntentionAction} is not yet known.
 * The real behavior becomes known later, when the {@link #getLspIntentionAction() lspIntentionAction} property is initialized.
 * {@link LspIntentionAction} object holds the {@link CodeAction} object, which is a response from the LSP server that contains all the information
 * needed to make this class actionable.
 * <p>
 * {@code IntentionAction} has no {@code getFamilyName()} and no {@code generatePreview()};
 * {@link #getFamilyName()} is a plain method, and there is no preview.
 * <p>
 * The Alt+Enter handler ({@code ShowIntentionActionsHandler}) calls
 * {@code isAvailable} on the UI thread and its background {@code ShowIntentionsPass} does not run in the desktop editor
 * ({@code HasFocus.hasFocus(editor.getContentUIComponent())} is always {@code false}). So while an action is being performed on
 * the UI thread (Alt+Enter, see {@link ActionContextElement}), the LSP requests are sent from a background thread under a
 * cancellable modal progress ({@link #computeUnderModalProgress}); otherwise (e.g. the error tooltip) only the computed results
 * are used.
 */
public abstract class LspIntentionActionWrapperBase implements IntentionAction, Comparable<IntentionAction>, DumbAware {
    /**
     * The performed action whose modal progress was canceled by the user: the other wrappers don't start a new progress for it.
     */
    private static volatile @Nullable ActionContextElement ourCanceledActionContext;

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

    /**
     * Returns {@code true} if a modal progress may be shown on the current (UI) thread, i.e. the user performs an action
     * on this thread (Alt+Enter) and has not canceled a progress started for it.
     */
    public static boolean canComputeUnderModalProgress() {
        if (!Application.get().isDispatchThread()) {
            return false;
        }
        ActionContextElement actionContext = ActionContextElement.getActionThreadContext();
        return actionContext != null && actionContext != ourCanceledActionContext;
    }

    /**
     * Runs {@code computation} in a read action in a background thread under a cancellable modal progress, see the class
     * Javadoc. Must be called on the UI thread when {@link #canComputeUnderModalProgress()} is {@code true}.
     *
     * @return {@code null} if the user canceled the progress
     */
    public static <T> @Nullable T computeUnderModalProgress(Project project, Supplier<T> computation) {
        try {
            return ProgressManager.getInstance().runProcessWithProgressSynchronously(
                (ThrowableSupplier<T, RuntimeException>) () -> ReadAction.compute(computation::get),
                LspLocalize.lspCodeActionsProgressTitle(),
                true,
                project
            );
        }
        catch (ProcessCanceledException e) {
            ourCanceledActionContext = ActionContextElement.getActionThreadContext();
            return null;
        }
    }

    @Override
    public void invoke(Project project, Editor editor, PsiFile psiFile) {
        LspIntentionAction action = getLspIntentionAction();
        if (action != null) {
            action.invoke(project, editor, psiFile);
        }
    }
}

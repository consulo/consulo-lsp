package com.intellij.platform.lsp.impl.features.intention;

import com.intellij.platform.lsp.api.customization.LspIntentionAction;
import com.intellij.platform.lsp.impl.features.quickFix.LspIntentionActionWrapperBase;
import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.codeEditor.Editor;
import consulo.language.editor.inject.EditorWindow;
import consulo.language.psi.PsiFile;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The 15 subclasses {@code LspIntention0..14} are the registered {@code intentionAction} extensions
 * ({@code @ExtensionImpl} + {@code @IntentionMetaData}).
 */
public class LspIntentionActionWrapper extends LspIntentionActionWrapperBase {
    private volatile @Nullable LspIntentionAction myLspIntentionAction;

    public LspIntentionActionWrapper(int index) {
        super(index);
    }

    @Override
    public @Nullable LspIntentionAction getLspIntentionAction() {
        return myLspIntentionAction;
    }

    @Override
    public void setLspIntentionAction(@Nullable LspIntentionAction lspIntentionAction) {
        myLspIntentionAction = lspIntentionAction;
    }

    @Override
    public LocalizeValue getFamilyName() {
        return LspLocalize.intentionFamilyName();
    }

    @Override
    public boolean isAvailable(Project project, Editor editor, PsiFile psiFile) {
        if (editor instanceof EditorWindow) {
            return false;
        }

        ensureInitialized(editor);
        return super.isAvailable(project, editor, psiFile);
    }

    private void ensureInitialized(Editor editor) {
        Project project = editor.getProject();
        if (project == null) {
            return;
        }
        if (project.isDefault()) {
            return;
        }
        List<LspIntentionAction> intentionActions = LspIntentionActionService.getInstance(project).getIntentionActions(editor);
        myLspIntentionAction = getIndex() < intentionActions.size() ? intentionActions.get(getIndex()) : null;
    }
}

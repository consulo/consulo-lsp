package com.intellij.platform.lsp.impl.features.documentation;

import com.intellij.platform.lsp.api.customization.LspHoverSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.impl.features.completion.LspCompletionObject;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.codeEditor.Editor;
import consulo.document.util.TextRange;
import consulo.language.editor.documentation.DocumentationProviderEx;
import consulo.language.editor.documentation.UnrestrictedDocumentationProvider;
import consulo.language.impl.psi.FakePsiElement;
import consulo.language.inject.InjectedLanguageManager;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.project.Project;
import consulo.ui.ex.localize.UILocalize;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.MarkupContent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * An {@link UnrestrictedDocumentationProvider} (a documentation provider for all languages):
 * <ul>
 *   <li>{@link #getCustomDocumentationElement} is asked for the documentation target at an offset (Ctrl+Q,
 *   the documentation on mouse hover) before the PSI-based search. It returns the first {@link LspDocumentationTarget}
 *   computed by {@link #documentationTargets}; a {@link LspDocumentationTarget} is a fake PSI element.</li>
 *   <li>{@link #getDocumentationElementForLookupItem} wraps the {@link LspCompletionObject} of an LSP lookup element into a
 *   {@link LspCompletionDocumentationTarget}.</li>
 *   <li>{@link #generateDoc} computes the documentation of both targets.</li>
 * </ul>
 */
@ExtensionImpl(id = "LspDocumentationTargetProvider")
public class LspDocumentationTargetProvider extends DocumentationProviderEx implements UnrestrictedDocumentationProvider {
    @RequiredReadAction
    public List<LspDocumentationTarget> documentationTargets(PsiFile psiFile, int offset) {
        Project project = psiFile.getProject();
        if (project.isDefault()) {
            return List.of();
        }

        InjectedLanguageManager injectedLanguageManager = InjectedLanguageManager.getInstance(project);
        PsiFile hostPsiFile = injectedLanguageManager.getTopLevelFile(psiFile);
        if (hostPsiFile == null) {
            return List.of();
        }
        int offsetInHost = injectedLanguageManager.injectedToHost(psiFile, offset);
        VirtualFile file = hostPsiFile.getVirtualFile();
        if (file == null) {
            return List.of();
        }

        List<LspDocumentationTarget> result = new ArrayList<>();
        for (LspClientImpl lspClient : LspClientManagerImpl.getInstanceImpl(project).getClientsForFileRequests(file)) {
            if (!(lspClient.getDescriptor().getLspCustomization().getHoverCustomizer() instanceof LspHoverSupport)) {
                continue;
            }
            if (!LspServerCapabilitiesKt.supportsHover(lspClient)) {
                continue;
            }

            // When `DocumentationTargetProvider.documentationTargets()` is called on hover, it is cancellable,
            // so using a bigger timeout wouldn't ever cause freezes.
            // However, the situation is different if the Quick Documentation action has been caused by a shortcut pressed.
            // In this case, the `AnAction.update()` method deliberately blocks EDT: although `AnAction.update()` runs in a background thread,
            // it is not cancellable when processing a shortcut.
            // This is done this way to be sure that the state doesn't change between `AnAction.update()` and `AnAction.actionPerformed()`.
            // The sign of such a not cancellable update is the `runUpdateSessionForInputEvent()` method in the stack trace.
            // Unfortunately, there's no reliable way to check whether the current `DocumentationTargetProvider.documentationTargets()`
            // call is cancellable or not. So, let's stay on the safe side and use small timeout.
            // According to the experiments, it is enough for LSP servers.
            int timeoutMs = LspClientImpl.NOT_CANCELLABLE_REQUEST_TIMEOUT_MS;

            TextRangeAndMarkupContent textRangeAndMarkupContent =
                lspClient.getRequestExecutor().getHoverCaching(file, offsetInHost, timeoutMs);
            if (textRangeAndMarkupContent != null) {
                TextRange textRange = textRangeAndMarkupContent.getTextRange();
                String presentableText = textRange.getLength() > 0 && textRange.getEndOffset() <= hostPsiFile.getTextLength()
                    ? textRange.substring(hostPsiFile.getText())
                    : null;
                result.add(new LspDocumentationTarget(presentableText, textRangeAndMarkupContent.getMarkupContent(), project, hostPsiFile));
            }
        }
        return result;
    }

    @Override
    @RequiredReadAction
    public @Nullable PsiElement getCustomDocumentationElement(
        Editor editor,
        PsiFile file,
        @Nullable PsiElement contextElement,
        int targetOffset
    ) {
        List<LspDocumentationTarget> targets = documentationTargets(file, targetOffset);
        return targets.isEmpty() ? null : targets.get(0);
    }

    @Override
    public @Nullable PsiElement getDocumentationElementForLookupItem(PsiManager psiManager, Object object, PsiElement element) {
        if (object instanceof LspCompletionObject completionObject) {
            return new LspCompletionDocumentationTarget(completionObject, element);
        }
        return null;
    }

    @Override
    @RequiredReadAction
    public @Nullable String generateDoc(PsiElement element, @Nullable PsiElement originalElement) {
        if (element instanceof LspDocumentationTarget target) {
            return target.computeDocumentation();
        }
        if (element instanceof LspCompletionDocumentationTarget target) {
            return target.getCompletionObject().computeDocumentation();
        }
        return null;
    }

    /**
     * The documentation target of the hover result, as a fake (non-physical) PSI element, so that the documentation
     * component keeps a hard pointer to it.
     */
    public static final class LspDocumentationTarget extends FakePsiElement {
        private final @Nullable String myPresentableText;
        private final MarkupContent myMarkupContent;
        private final Project myProject;
        private final PsiFile myPsiFile;

        private LspDocumentationTarget(@Nullable String presentableText, MarkupContent markupContent, Project project, PsiFile psiFile) {
            myPresentableText = presentableText;
            myMarkupContent = markupContent;
            myProject = project;
            myPsiFile = psiFile;
        }

        @Override
        public PsiElement getParent() {
            return myPsiFile;
        }

        @Override
        public PsiFile getContainingFile() {
            return myPsiFile;
        }

        @Override
        public Project getProject() {
            return myProject;
        }

        @Override
        public boolean isPhysical() {
            return false;
        }

        @Override
        public boolean isValid() {
            return myPsiFile.isValid();
        }

        @Override
        public String getName() {
            return myPresentableText != null ? myPresentableText : UILocalize.toolWindowNameDocumentation().get();
        }

        @RequiredReadAction
        public String computeDocumentation() {
            return LspDocumentationData.createLspDocumentationData(myMarkupContent).toQuickDocHtml(myProject);
        }
    }

    /**
     * The documentation target of an LSP lookup element.
     */
    public static final class LspCompletionDocumentationTarget extends FakePsiElement {
        private final LspCompletionObject myCompletionObject;
        private final PsiElement myContext;

        private LspCompletionDocumentationTarget(LspCompletionObject completionObject, PsiElement context) {
            myCompletionObject = completionObject;
            myContext = context;
        }

        public LspCompletionObject getCompletionObject() {
            return myCompletionObject;
        }

        @Override
        public PsiElement getParent() {
            return myContext;
        }

        @Override
        public Project getProject() {
            return myCompletionObject.getLspClient().getProject();
        }

        @Override
        public boolean isPhysical() {
            return false;
        }

        @Override
        public boolean isValid() {
            return myContext.isValid();
        }

        @Override
        public String getName() {
            return myCompletionObject.getCompletionItem().getLabel();
        }
    }
}

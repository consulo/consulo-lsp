package com.intellij.platform.lsp.impl.features.hierarchy;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.application.Application;
import consulo.codeEditor.Editor;
import consulo.component.ProcessCanceledException;
import consulo.dataContext.DataContext;
import consulo.language.Language;
import consulo.language.editor.hierarchy.HierarchyKind;
import consulo.language.editor.hierarchy.HierarchyProvider;
import consulo.language.psi.PsiDocumentManager;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.dataholder.Key;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Position;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Top-level functions of upstream {@code LspHierarchyUtil.kt}.
 */
public final class LspHierarchyUtilKt {
    private static final Logger LOG = Logger.getInstance(LspHierarchyUtilKt.class);

    private LspHierarchyUtilKt() {
    }

    /**
     * Consulo: upstream {@code EditorContextManager.getPsiFileForEditor(editor, project)} is
     * {@code PsiDocumentManager.getPsiFile(editor.getDocument())}.
     */
    @RequiredReadAction
    public static @Nullable PsiElement getTargetFromEditor(
        DataContext dataContext,
        Key<LspClientImpl> clientKey,
        BiPredicate<LspClientImpl, VirtualFile> clientPredicate
    ) {
        Project project = dataContext.getData(Project.KEY);
        if (project == null) {
            return null;
        }
        Editor editor = dataContext.getData(Editor.KEY);
        if (editor == null) {
            return null;
        }
        int caretOffset = editor.getCaretModel().getOffset();
        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (psiFile == null) {
            return null;
        }

        LspClientImpl client = findSupportingClient(project, psiFile.getViewProvider().getVirtualFile(), clientPredicate);
        if (client == null) {
            return null;
        }
        Position position = Lsp4jUtilKt.getLsp4jPosition(editor.getDocument(), caretOffset);

        LspFakePsiElement element = new LspFakePsiElement(psiFile, position);
        element.putUserData(clientKey, client);
        return element;
    }

    @RequiredReadAction
    public static @Nullable LspClientImpl findSupportingClient(
        Project project,
        VirtualFile file,
        BiPredicate<LspClientImpl, VirtualFile> clientPredicate
    ) {
        for (LspClientImpl client : LspClientManagerImpl.getInstanceImpl(project).getRunningClients()) {
            if (client.isSupportedFile(file) && clientPredicate.test(client, file)) {
                return client;
            }
        }
        return null;
    }

    /**
     * Consulo only. Upstream registers the LSP hierarchy providers with {@code order="last"}, so a provider of the file's
     * language is asked first and wins. Consulo reserves the {@code first}/{@code last} orders for the plugin that owns
     * the extension point, so the LSP providers (registered for {@link Language#ANY}) step aside instead: this returns
     * {@code true} when a provider of the same {@code kind} registered for the language of the context element or file
     * (or one of their base languages) offers a target in this context.
     */
    @RequiredReadAction
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean hasLanguageSpecificTarget(DataContext dataContext, HierarchyKind kind) {
        Set<Language> languages = new HashSet<>();
        collectLanguages(dataContext.getData(PsiElement.KEY), languages);
        collectLanguages(dataContext.getData(PsiFile.KEY), languages);
        languages.remove(Language.ANY);
        if (languages.isEmpty()) {
            return false;
        }

        for (HierarchyProvider provider : Application.get().getExtensionPoint(HierarchyProvider.class).getExtensionList()) {
            Language language = provider.getLanguage();
            if (language == Language.ANY || !languages.contains(language) || !kind.equals(provider.getKind())) {
                continue;
            }
            try {
                if (provider.getTarget(dataContext) != null) {
                    return true;
                }
            }
            catch (ProcessCanceledException e) {
                throw e;
            }
            catch (Exception e) {
                LOG.error(e);
            }
        }
        return false;
    }

    @RequiredReadAction
    private static void collectLanguages(@Nullable PsiElement element, Set<Language> languages) {
        if (element == null) {
            return;
        }
        Language language = element.getLanguage();
        while (language != null) {
            languages.add(language);
            language = language.getBaseLanguage();
        }
    }
}

package com.intellij.platform.lsp.impl.features.usages;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.customization.LspFindReferencesSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.codeEditor.Editor;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.language.editor.inject.EditorWindow;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.project.Project;
import consulo.usage.PsiElementUsageTargetFactory;
import consulo.usage.UsageTarget;
import consulo.usage.UsageTargetProvider;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Position;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Helps to implement the 'Find Usages' and 'Show Usages' features backed by an LSP server
 * (<a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_references">textDocument/references</a> request).
 * <p>
 * The usage targets of the 'Find Usages' and 'Show Usages' actions come from {@link UsageTargetProvider}s
 * ({@code UsageView.USAGE_TARGETS_KEY}). The LSP target is a {@link LspSearchTarget} PSI element wrapped into a
 * {@code PsiElementUsageTarget}; the search itself is done by the {@link LspUsageSearcher} find usages handler.
 *
 * @see LspUsageSearcher
 */
@ExtensionImpl
public class LspSearchTargetsRule implements UsageTargetProvider {
    @Override
    @RequiredReadAction
    public UsageTarget @Nullable [] getTargets(Editor editor, PsiFile file) {
        LspSearchTarget searchTarget = searchTargets(editor);
        if (searchTarget == null) {
            return null;
        }
        return new UsageTarget[]{PsiElementUsageTargetFactory.getInstance().create(searchTarget)};
    }

    @Override
    public UsageTarget @Nullable [] getTargets(PsiElement psiElement) {
        return null;
    }

    private @Nullable LspSearchTarget searchTargets(Editor editor) {
        if (editor instanceof EditorWindow) {
            return null;
        }
        Project project = editor.getProject();
        if (project == null || project.isDefault()) {
            return null;
        }
        Document document = editor.getDocument();
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null) {
            return null;
        }
        int offset = editor.getCaretModel().getOffset();
        Position position = Lsp4jUtilKt.getLsp4jPosition(document, offset);

        List<LspClient> lspClients = new ArrayList<>();
        for (LspClientImpl lspClient : LspClientManagerImpl.getInstanceImpl(project).getClientsWithThisFileOpen(file)) {
            if (isFindReferencesEnabledFor(lspClient, file)) {
                lspClients.add(lspClient);
            }
        }
        if (lspClients.isEmpty()) {
            return null;
        }

        return new LspSearchTarget(lspClients, file, position);
    }

    public static boolean isFindReferencesEnabledFor(LspClientImpl lspClient, VirtualFile file) {
        return lspClient.getDescriptor().getLspCustomization().getFindReferencesCustomizer() instanceof LspFindReferencesSupport &&
            LspServerCapabilitiesKt.supportsFindReferences(lspClient, file);
    }
}

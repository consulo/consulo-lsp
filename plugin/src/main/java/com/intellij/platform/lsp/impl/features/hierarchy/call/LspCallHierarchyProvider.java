package com.intellij.platform.lsp.impl.features.hierarchy.call;

import com.intellij.platform.lsp.api.customization.LspCallHierarchyCustomizer;
import com.intellij.platform.lsp.api.customization.LspCallHierarchySupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.impl.features.hierarchy.LspHierarchyUtilKt;
import consulo.language.psi.PsiFile;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.dataContext.DataContext;
import consulo.language.Language;
import consulo.language.editor.hierarchy.HierarchyKind;
import consulo.language.editor.hierarchy.HierarchyModel;
import consulo.language.editor.hierarchy.HierarchyProvider;
import consulo.language.editor.hierarchy.StandardHierarchyKinds;
import consulo.language.psi.PsiElement;
import consulo.project.Project;
import consulo.util.dataholder.Key;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

/**
 * Consulo: upstream registers it with {@code language=""} and {@code order="last"}; here {@link #getLanguage()} returns
 * {@link Language#ANY} and, instead of the reserved {@code last} order, {@link #getTarget} steps aside when a provider of
 * the file's language has a target ({@link LspHierarchyUtilKt#hasLanguageSpecificTarget}).
 * Upstream {@code createHierarchyBrowser} is {@link #createModel}, {@code browserActivated} (switch to the caller view)
 * is {@link LspCallHierarchyBrowser#getDefaultViewType()}.
 */
@ExtensionImpl
public class LspCallHierarchyProvider implements HierarchyProvider<PsiElement> {
    private static final Key<LspClientImpl> LSP_CALL_HIERARCHY_CLIENT_KEY = Key.create("lsp.call.hierarchy.client");

    @Override
    public Language getLanguage() {
        return Language.ANY;
    }

    @Override
    public HierarchyKind getKind() {
        return StandardHierarchyKinds.CALL;
    }

    @Override
    @RequiredReadAction
    public @Nullable PsiElement getTarget(DataContext dataContext) {
        if (LspHierarchyUtilKt.hasLanguageSpecificTarget(dataContext, getKind())) {
            return null;
        }
        return LspHierarchyUtilKt.getTargetFromEditor(dataContext, LSP_CALL_HIERARCHY_CLIENT_KEY, this::shouldProceedWithClient);
    }

    @Override
    @RequiredReadAction
    public HierarchyModel<PsiElement> createModel(Project project, PsiElement target) {
        // Client is cached in user data by getTarget(), but BaseOnThis* actions bypass getTarget()
        // and call createHierarchyBrowser() directly, so we need to find the supporting client
        LspClientImpl client = target.getUserData(LSP_CALL_HIERARCHY_CLIENT_KEY);
        PsiFile containingFile = target.getContainingFile();
        VirtualFile targetFile = containingFile != null ? containingFile.getVirtualFile() : null;
        if (client == null && targetFile != null) {
            client = LspHierarchyUtilKt.findSupportingClient(
                target.getProject(),
                targetFile,
                this::shouldProceedWithClient
            );
        }
        if (client == null) {
            throw new IllegalStateException("Missing cached LSP client on call-hierarchy target");
        }
        return new LspCallHierarchyBrowser(target.getProject(), target, client);
    }

    private boolean shouldProceedWithClient(LspClientImpl client, VirtualFile file) {
        LspCallHierarchyCustomizer customizer = client.getDescriptor().getLspCustomization().getCallHierarchyCustomizer();
        return customizer instanceof LspCallHierarchySupport support &&
            LspServerCapabilitiesKt.supportsCallHierarchy(client, file) &&
            support.shouldAskServerForCallHierarchy(file);
    }
}

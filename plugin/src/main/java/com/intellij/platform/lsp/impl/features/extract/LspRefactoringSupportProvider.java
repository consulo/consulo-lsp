package com.intellij.platform.lsp.impl.features.extract;

import com.intellij.platform.lsp.api.customization.LspCodeActionsCustomizer;
import com.intellij.platform.lsp.api.customization.LspCodeActionsSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.impl.features.LspPendingClientKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.language.Language;
import consulo.language.editor.refactoring.RefactoringSupportProvider;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Supplies one Extract handler and gates it on the support of the handler's own code action kinds,
 * so a server that supports one Extract subtype does not enable the other Extract actions.
 * The {@link #myKinds} list is a preference order: the handler requests a later kind only when no earlier kind yields an action.
 * The variable and field extractions fall back to {@code refactor.extract.constant},
 * because a server can serve its only scoped-value extraction under that kind.
 * In a class scope that extraction yields a field.
 * Inline resolves through {@link com.intellij.platform.lsp.impl.features.inline.LspInlineActionHandler} and needs no provider,
 * so an inline-only server does not enable the Extract actions.
 * <p>
 * Consulo: upstream registers the four providers with {@code language=""} and {@code order="last"}. Here they are registered for
 * {@link Language#ANY} without an order (Consulo reserves {@code first}/{@code last} for the plugin that owns the extension point).
 * Consulo's {@link RefactoringSupportProvider#forLanguage} is a one-to-one lookup by the file language (and its base languages),
 * so a language-specific provider always wins; it never falls back to the {@link Language#ANY} providers, so these providers
 * are not used until the platform looks them up (see the porting notes).
 */
public abstract sealed class LspRefactoringSupportProvider extends RefactoringSupportProvider
    permits LspRefactoringSupportProviderIntroduceVariable,
    LspRefactoringSupportProviderExtractMethod,
    LspRefactoringSupportProviderIntroduceField,
    LspRefactoringSupportProviderIntroduceConstant {

    protected final List<String> myKinds;

    protected LspRefactoringSupportProvider(List<String> kinds) {
        myKinds = kinds;
    }

    @Override
    public Language getLanguage() {
        return Language.ANY;
    }

    @Override
    @RequiredReadAction
    public final boolean isAvailable(PsiElement context) {
        PsiFile psiFile = context.getContainingFile();
        if (psiFile == null) {
            return false;
        }
        return !findLspClientsForExtract(psiFile, myKinds).isEmpty() || findPendingLspClientForExtract(psiFile) != null;
    }

    public static List<LspClientImpl> findLspClientsForExtract(PsiFile psiFile, List<String> kinds) {
        VirtualFile virtualFile = psiFile.getVirtualFile();
        if (virtualFile == null) {
            return List.of();
        }
        List<LspClientImpl> result = new ArrayList<>();
        for (LspClientImpl client : LspClientManagerImpl.getInstanceImpl(psiFile.getProject()).getClientsWithThisFileOpen(virtualFile)) {
            if (!optsInToExtract(client, psiFile)) {
                continue;
            }
            for (String kind : kinds) {
                if (LspServerCapabilitiesKt.supportsCodeActionsOfKind(client, virtualFile, kind)) {
                    result.add(client);
                    break;
                }
            }
        }
        return result;
    }

    public static @Nullable LspClientImpl findPendingLspClientForExtract(PsiFile psiFile) {
        return LspPendingClientKt.findPendingLspClient(psiFile, it -> optsInToExtract(it, psiFile));
    }

    private static boolean optsInToExtract(LspClientImpl lspClient, PsiFile psiFile) {
        LspCodeActionsCustomizer customizer = lspClient.getDescriptor().getLspCustomization().getCodeActionsCustomizer();
        return customizer instanceof LspCodeActionsSupport support
            && support.getExtractRefactoringSupport()
            && support.shouldRunRefactoring(psiFile);
    }
}

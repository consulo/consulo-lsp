package com.intellij.platform.lsp.impl.features;

import consulo.application.Application;
import consulo.language.editor.DaemonCodeAnalyzer;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;

public final class LspFeaturesRefreshing {
    private LspFeaturesRefreshing() {
    }

    /**
     * Upstream: {@code application.messageBus.syncPublisher(BreadcrumbsXmlWrapper.FORCE_RELOAD_BREADCRUMBS).run()}.
     * Consulo has no {@code FORCE_RELOAD_BREADCRUMBS} topic (and no {@code FileBreadcrumbsCollector} extension), so there is nothing to reload.
     */
    public static void refreshBreadcrumbs() {
    }

    /**
     * Restarts the daemon for the file, so the next {@code LineMarkersPass} reads the fresh
     * {@code LspInheritanceMarkersCache} snapshot.
     */
    public static void refreshLineMarkers(Project project, VirtualFile file) {
        Application.get().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (psiFile == null) {
                return;
            }
            DaemonCodeAnalyzer.getInstance(project).restart(psiFile);
        });
    }

    public static void refreshCodeLenses(Project project) {
        // TODO codeLens stage: upstream invalidates the LSP code vision provider on the EDT:
        //  project.service<CodeVisionHost>().invalidateProvider(CodeVisionHost.LensInvalidateSignal(null, listOf(LSP_CODE_VISION_PROVIDER_ID)))
        //  Consulo has no public CodeVisionHost API.
    }
}

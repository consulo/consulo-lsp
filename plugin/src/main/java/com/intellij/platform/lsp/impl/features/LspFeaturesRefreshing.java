package com.intellij.platform.lsp.impl.features;

import com.intellij.platform.lsp.impl.features.codeLens.LspCodeVisionProvider;
import consulo.application.Application;
import consulo.codeEditor.Editor;
import consulo.codeEditor.EditorFactory;
import consulo.language.editor.DaemonCodeAnalyzer;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.language.psi.PsiModificationTracker;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;

import java.util.LinkedHashSet;
import java.util.Set;

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
     * {@link com.intellij.platform.lsp.impl.features.lineMarkers.LspInheritanceMarkersCache LspInheritanceMarkersCache} snapshot.
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

    /**
     * Consulo: upstream invalidates the LSP code vision provider on the EDT:
     * {@code CodeVisionHost.invalidateProvider(LensInvalidateSignal(null, listOf(LSP_CODE_VISION_PROVIDER_ID)))}.
     * Consulo has no {@code CodeVisionHost}; the lenses of {@link LspCodeVisionProvider} are computed by the code vision
     * highlighting pass, which is skipped while the PSI modification count is unchanged (the API to reset its stamp,
     * {@code CodeVisionPassFactory.resetModificationStamp}, is not public). So the PSI modification counter is incremented and
     * the daemon is restarted for the files shown in the editors of the project.
     */
    public static void refreshCodeLenses(Project project) {
        Application application = Application.get();
        application.invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            Set<PsiFile> psiFiles = new LinkedHashSet<>();
            for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
                if (editor.getProject() != project) {
                    continue;
                }
                VirtualFile file = editor.getVirtualFile();
                if (file == null || !file.isValid()) {
                    continue;
                }
                PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                if (psiFile != null) {
                    psiFiles.add(psiFile);
                }
            }
            if (psiFiles.isEmpty()) {
                return;
            }
            application.runWriteAction(() -> PsiModificationTracker.getInstance(project).incCounter());
            DaemonCodeAnalyzer daemonCodeAnalyzer = DaemonCodeAnalyzer.getInstance(project);
            for (PsiFile psiFile : psiFiles) {
                daemonCodeAnalyzer.restart(psiFile);
            }
        }, project.getDisposed());
    }
}

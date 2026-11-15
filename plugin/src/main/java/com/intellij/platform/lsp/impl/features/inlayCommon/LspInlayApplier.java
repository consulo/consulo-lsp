package com.intellij.platform.lsp.impl.features.inlayCommon;

import com.intellij.platform.lsp.impl.features.inlayHint.LspInlayHintRenderingKt;
import com.intellij.platform.lsp.impl.features.inlayHintColor.LspColorInlayRenderingKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.Application;
import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.codeEditor.Editor;
import consulo.codeEditor.EditorFactory;
import consulo.language.editor.DaemonCodeAnalyzer;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.language.psi.PsiModificationTracker;
import consulo.project.Project;
import consulo.util.collection.ContainerUtil;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Applies LSP inline inlays (inlay hints and document color) to the editor when a server response arrives.
 * A single applier collects ALL features together ({@link #collectInlays}) under one per-file generation counter,
 * rather than one applier per feature.
 * <p>
 * Consulo: upstream paints the inlays out of band, directly into the editor {@code InlayModel} with IntelliJ's
 * {@code PresentationFactory}/{@code PresentationRenderer} (minimal diff by offset + identity). Consulo has no public
 * presentation renderer, so the inlays are rendered by Consulo's declarative inlay hints pass instead:
 * {@link LspInlayHintsProvider} collects {@link #collectInlays} into the declarative sink, and the pass does the diffing.
 * {@link #scheduleRefresh} therefore makes the declarative pass re-run for the file. The pass skips files whose PSI
 * modification count has not changed, and the API to reset its stamp
 * ({@code DeclarativeInlayHintsPassFactory.scheduleRecompute}) is not public, so the PSI modification counter is
 * incremented before the daemon restart. The global inlay hints switch is handled by the declarative pass itself, so
 * the upstream {@code InlayHintsSettings.INLAY_SETTINGS_CHANGED} listener is not needed.
 */
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
@Singleton
public class LspInlayApplier {
    private final Project myProject;

    // Weak keys: the daemon schedules refreshes for every editor, including the in-memory files of diff viewers and
    // dialogs, which never reach [onFileClosed].
    private final ConcurrentMap<VirtualFile, AtomicLong> myFileToCurrentGeneration = ContainerUtil.createConcurrentWeakMap();
    private final ExecutorService mySerializedExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor("LspInlayApplier", 1);

    @Inject
    public LspInlayApplier(Project project) {
        myProject = project;
    }

    public static LspInlayApplier getInstance(Project project) {
        return project.getInstance(LspInlayApplier.class);
    }

    /**
     * Called when any backing cache (inlay hint, document color) is updated for {@code file}. Increments the per-file
     * generation and launches a task that makes the inlays of the file re-collected and re-applied. If a newer refresh
     * has already bumped the generation before the task starts, the stale task bails.
     */
    public void scheduleRefresh(VirtualFile file) {
        if (file instanceof VirtualFileWindow) {
            return;
        }

        AtomicLong generationCounter = myFileToCurrentGeneration.computeIfAbsent(file, it -> new AtomicLong());
        long gen = generationCounter.incrementAndGet();
        mySerializedExecutor.execute(() -> {
            if (generationCounter.get() > gen) {
                return;
            }
            Application application = Application.get();
            application.invokeLater(() -> {
                if (generationCounter.get() > gen || !file.isValid()) {
                    return;
                }
                // InlayModel is per-editor, so re-apply only if some editor currently shows this file.
                List<Editor> editors = new ArrayList<>();
                for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
                    if (Objects.equals(editor.getVirtualFile(), file) && editor.getProject() == myProject) {
                        editors.add(editor);
                    }
                }
                if (editors.isEmpty()) {
                    return;
                }
                PsiFile psiFile = PsiManager.getInstance(myProject).findFile(file);
                if (psiFile == null) {
                    return;
                }
                // Resets the PSI-stamp check of the declarative inlay hints pass (see the class comment).
                application.runWriteAction(() -> PsiModificationTracker.getInstance(myProject).incCounter());
                DaemonCodeAnalyzer.getInstance(myProject).restart(psiFile);
            }, myProject.getDisposed());
        });
    }

    /**
     * Releases per-file state once {@code file} is fully closed (no editor shows it anymore). The inlays were already disposed
     * with their editors' InlayModels; this just drops the generation counter so the map stays bounded.
     */
    public void onFileClosed(VirtualFile file) {
        myFileToCurrentGeneration.remove(file);
    }

    /**
     * Collects every LSP inlay item for {@code file} across features. Also triggers a server request per feature when its cache is stale.
     */
    @RequiredReadAction
    public List<LspInlayItem> collectInlays(VirtualFile file) {
        List<LspInlayItem> result = new ArrayList<>(LspInlayHintRenderingKt.collectInlayHintItems(myProject, file));
        result.addAll(LspColorInlayRenderingKt.collectColorInlayItems(myProject, file));
        return result;
    }
}

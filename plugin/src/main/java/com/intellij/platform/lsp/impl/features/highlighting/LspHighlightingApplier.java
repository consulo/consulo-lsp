// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.highlighting;

import com.intellij.platform.lsp.api.customization.LspDiagnosticsCustomizer;
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport;
import com.intellij.platform.lsp.api.customization.LspDocumentLinkDisabled;
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspCachedHighlighting;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.Application;
import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.codeEditor.CodeInsightColors;
import consulo.colorScheme.TextAttributes;
import consulo.colorScheme.TextAttributesKey;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.document.util.TextRange;
import consulo.language.editor.annotation.Annotation;
import consulo.language.editor.annotation.AnnotationSession;
import consulo.language.editor.annotation.HighlightSeverity;
import consulo.language.editor.highlight.UpdateHighlightersUtil;
import consulo.language.editor.inspection.ProblemHighlightType;
import consulo.language.editor.rawHighlight.HighlightInfo;
import consulo.language.editor.rawHighlight.HighlightInfoType;
import consulo.language.editor.util.HighlightTypeUtil;
import consulo.language.editor.wolfAnalyzer.Problem;
import consulo.language.editor.wolfAnalyzer.WolfTheProblemSolver;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.localize.LocalizeValue;
import consulo.lsp.impl.compat.AnnotationHolderImpl;
import consulo.lsp.impl.compat.HyperlinkAnnotator;
import consulo.project.Project;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.ThreeState;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Applies LSP highlighting reactively without restarting the daemon.
 * <p>
 * When caches are updated (diagnostics received, semantic tokens refreshed, etc.),
 * {@link #scheduleHighlightingRefresh} launches a task on a <b>serialized executor</b> that converts cache data
 * to {@link HighlightInfo} and applies it via {@link UpdateHighlightersUtil#setHighlightersToEditor} on the EDT.
 * No {@link consulo.language.editor.DaemonCodeAnalyzer#restart} is involved, so other inspections
 * continue undisturbed.
 * <p>
 * A per-file cache generation counter deduplicates rapid updates: if a later {@link #scheduleHighlightingRefresh} has already
 * incremented the cache generation, an earlier task skips the expensive apply.
 * "Cache generation" = a monotonic counter incremented each time any LSP cache (diagnostics, semantic tokens, etc.)
 * is updated for a given file.
 */
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
@Singleton
public class LspHighlightingApplier {
    private static final String LSP_EXTERNAL_SOURCE = "LSP";
    public static final int UNREGISTERED_PASS_ID = -1;

    /**
     * How long {@link #scheduleHighlightingRefreshDebounced} waits, so one refresh serves a whole typing burst.
     */
    public static final Duration EDIT_DEBOUNCE = Duration.ofMillis(40);

    /**
     * Widens or disables the debounce window in tests, where the production value is too
     * timing-sensitive to assert against.
     */
    private static volatile @Nullable Duration ourEditDebounceOverride = null;

    /**
     * Assigned by the platform via {@link consulo.language.editor.highlight.TextEditorHighlightingPassFactory.Registrar#registerTextEditorHighlightingPass}.
     * Volatile because it's written from the registration thread and read from highlighting threads.
     */
    public static volatile int GROUP_ID = UNREGISTERED_PASS_ID;

    private final Project myProject;

    // Weak keys: the daemon pass reports for every editor, including the in-memory files of diff viewers and dialogs,
    // which never get a file-closed event. Entries are not dropped on close either: a refresh still in flight would
    // then report a generation above the restarted counter, and the Wolf gate would drop the newer reports.
    private final ConcurrentMap<VirtualFile, AtomicLong> myFileToCurrentGeneration = ContainerUtil.createConcurrentWeakMap();
    private final Set<VirtualFile> myFilesWithWolfReportedErrors = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<VirtualFile, Long> myFileToLastWolfWrittenGen = new WeakHashMap<>();

    private final ExecutorService mySerializedExecutor =
        AppExecutorUtil.createBoundedApplicationPoolExecutor("LspHighlightingApplier", 1);

    /**
     * Invoked after the collect read action and before the EDT apply. Lets a test interleave an edit deterministically.
     */
    private volatile @Nullable Consumer<VirtualFile> myAfterCollectHook = null;

    @Inject
    public LspHighlightingApplier(Project project) {
        myProject = project;
    }

    public static LspHighlightingApplier getInstance(Project project) {
        return project.getInstance(LspHighlightingApplier.class);
    }

    public static @Nullable Duration getEditDebounceOverride() {
        return ourEditDebounceOverride;
    }

    public static void setEditDebounceOverride(@Nullable Duration editDebounceOverride) {
        ourEditDebounceOverride = editDebounceOverride;
    }

    public @Nullable Consumer<VirtualFile> getAfterCollectHook() {
        return myAfterCollectHook;
    }

    public void setAfterCollectHook(@Nullable Consumer<VirtualFile> afterCollectHook) {
        myAfterCollectHook = afterCollectHook;
    }

    /**
     * Called when any LSP cache is updated for the given {@code file}.
     * Increments the cache generation counter and launches a task to apply highlights.
     * <p>
     * If multiple refreshes queue up before any of them starts running, only the latest task actually applies.
     */
    public void scheduleHighlightingRefresh(VirtualFile file) {
        AtomicLong generationCounter = myFileToCurrentGeneration.computeIfAbsent(file, it -> new AtomicLong());
        long gen = generationCounter.incrementAndGet();
        launchRefresh(file, generationCounter, gen);
    }

    /**
     * Like {@link #scheduleHighlightingRefresh}, but waits {@link #EDIT_DEBOUNCE} first, so a typing burst coalesces into
     * one refresh. The generation is claimed synchronously: this call supersedes every earlier refresh, and
     * any refresh scheduled later supersedes this one. The window is invisible on screen: the applied
     * highlighters are range markers and follow the edits meanwhile.
     */
    public void scheduleHighlightingRefreshDebounced(VirtualFile file) {
        AtomicLong generationCounter = myFileToCurrentGeneration.computeIfAbsent(file, it -> new AtomicLong());
        long gen = generationCounter.incrementAndGet();
        // The override is null in production; only tests set it.
        Duration override = ourEditDebounceOverride;
        Duration delay = override != null ? override : EDIT_DEBOUNCE;
        AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
            if (generationCounter.get() > gen) {
                return;
            }
            launchRefresh(file, generationCounter, gen);
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void launchRefresh(VirtualFile file, AtomicLong generationCounter, long gen) {
        mySerializedExecutor.execute(() -> {
            if (myProject.isDisposed() || generationCounter.get() > gen) {
                return;
            }
            HighlightsToApply highlights = readAction(() -> collectHighlightsForApply(file));
            if (highlights == null) {
                return;
            }
            Consumer<VirtualFile> hook = myAfterCollectHook;
            if (hook != null) {
                hook.accept(file);
            }
            Application.get().invokeLater(() -> {
                // An edit after the collect makes the ranges stale, and the edit itself scheduled a replacement refresh.
                if (highlights.myDocument.getModificationStamp() != highlights.myCollectedModStamp) {
                    return;
                }
                applyHighlightsToEditor(highlights);
                AppExecutorUtil.getAppExecutorService().execute(() -> reportErrorsToWolf(file, highlights.myHighlights, gen));
            }, myProject.getDisposed());
        });
    }

    @RequiredReadAction
    private @Nullable HighlightsToApply collectHighlightsForApply(VirtualFile file) {
        if (file instanceof VirtualFileWindow) {
            return null;
        }
        // The refresh is scheduled asynchronously, so the file may have been deleted/invalidated between the cache update and this read action
        if (!file.isValid() || myProject.isDisposed()) {
            return null;
        }
        PsiFile psiFile = PsiManager.getInstance(myProject).findFile(file);
        if (psiFile == null) {
            return null;
        }
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            return null;
        }
        int groupId = GROUP_ID;
        if (groupId == UNREGISTERED_PASS_ID) {
            return null;
        }
        List<HighlightInfo> highlights = collectHighlightInfos(psiFile, file, document);
        return new HighlightsToApply(document, highlights, groupId, document.getModificationStamp());
    }

    /**
     * Applies highlights to the editor from EDT, following the {@link UpdateHighlightersUtil#setHighlightersToEditor} pattern.
     */
    private void applyHighlightsToEditor(HighlightsToApply data) {
        if (myProject.isDisposed()) {
            return;
        }
        UpdateHighlightersUtil.setHighlightersToEditor(
            myProject, data.myDocument, 0, data.myDocument.getTextLength(), data.myHighlights, null, data.myGroupId
        );
    }

    private static final class HighlightsToApply {
        private final Document myDocument;
        private final List<HighlightInfo> myHighlights;
        private final int myGroupId;
        private final long myCollectedModStamp;

        private HighlightsToApply(Document document, List<HighlightInfo> highlights, int groupId, long collectedModStamp) {
            myDocument = document;
            myHighlights = highlights;
            myGroupId = groupId;
            myCollectedModStamp = collectedModStamp;
        }
    }

    @RequiredReadAction
    public List<HighlightInfo> collectHighlightInfos(PsiFile psiFile, VirtualFile file, Document document) {
        Collection<LspClientImpl> clients = LspClientManagerImpl.getInstanceImpl(myProject).getClientsWithThisFileOpen(file);
        if (clients.isEmpty()) {
            return List.of();
        }

        List<HighlightInfo> result = new ArrayList<>();
        for (LspClientImpl client : clients) {
            collectDiagnosticHighlightInfos(client, psiFile, file, document, result);
            collectSemanticTokenHighlightInfos(client, file, result);
            collectDocumentLinkHighlightInfos(client, file, result);
        }
        return result;
    }

    @RequiredReadAction
    private void collectDiagnosticHighlightInfos(
        LspClientImpl client,
        PsiFile psiFile,
        VirtualFile file,
        Document document,
        List<HighlightInfo> result
    ) {
        LspDiagnosticsCustomizer customizer = client.getDescriptor().getLspCustomization().getDiagnosticsCustomizer();
        if (!(customizer instanceof LspDiagnosticsSupport diagnosticsSupport)) {
            return;
        }
        List<DiagnosticAndQuickFixes> diagnosticsAndQuickFixes = client.getDiagnosticsAndQuickFixes(file);
        if (diagnosticsAndQuickFixes.isEmpty()) {
            return;
        }

        AnnotationSession session = new AnnotationSession(psiFile);

        // Consulo: AnnotationHolderImpl is a compat port (Consulo's own holder is internal); there is
        // no other way of converting LSP diagnostics to HighlightInfo outside an Annotator.
        AnnotationHolderImpl holder = new AnnotationHolderImpl(session, false);
        holder.runAnnotatorWithContext(psiFile);

        for (DiagnosticAndQuickFixes item : diagnosticsAndQuickFixes) {
            TextRange textRange = Lsp4jUtilKt.getRangeInDocument(document, item.getDiagnostic().getRange());
            if (textRange == null) {
                continue;
            }
            // The document can be ahead of the PSI: a publishDiagnostics notification may arrive after the user typed
            // but before the daemon reparses (commits) the file. AnnotationBuilder.range validates against the committed
            // PsiFile, so a range in the not-yet-reparsed tail would throw (IJPL-236846). Skip it this run; the reparse
            // triggers LspHighlightingPass, which re-runs this with the committed PSI and applies it properly.
            if (!psiFile.getTextRange().contains(textRange)) {
                continue;
            }
            holder.clear();
            diagnosticsSupport.createAnnotation(holder, item.getDiagnostic(), textRange, item.getQuickFixes());
            for (Annotation annotation : holder) {
                result.add(convertAnnotationToHighlightInfo(annotation));
            }
        }
    }

    @RequiredReadAction
    private void collectSemanticTokenHighlightInfos(LspClientImpl client, VirtualFile file, List<HighlightInfo> result) {
        if (!(client.getDescriptor().getLspCustomization().getSemanticTokensCustomizer()
            instanceof LspSemanticTokensSupport semanticTokensSupport)) {
            return;
        }
        List<LspCachedHighlighting<LspSemanticToken>> tokens =
            client.getHighlightingCacheRegistry().getSemanticTokensCache().getHighlightings(file);
        for (LspCachedHighlighting<LspSemanticToken> token : tokens) {
            TextAttributesKey textAttributesKey = semanticTokensSupport.getTextAttributesKey(
                token.getHighlightingInfo().getTokenType(), token.getHighlightingInfo().getTokenModifiers()
            );
            if (textAttributesKey == null) {
                continue;
            }
            HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.INFORMATION)
                .range(token.getTextRange())
                .textAttributes(textAttributesKey)
                .severity(HighlightSeverity.TEXT_ATTRIBUTES)
                .createUnconditionally();
            result.add(info);
        }
    }

    @RequiredReadAction
    private void collectDocumentLinkHighlightInfos(LspClientImpl client, VirtualFile file, List<HighlightInfo> result) {
        if (client.getDescriptor().getLspCustomization().getDocumentLinkCustomizer() instanceof LspDocumentLinkDisabled) {
            return;
        }
        List<LspCachedHighlighting<LspDocumentLink>> documentLinks =
            client.getHighlightingCacheRegistry().getDocumentLinkCache().getHighlightings(file);
        for (LspCachedHighlighting<LspDocumentLink> link : documentLinks) {
            HighlightInfo.Builder info = HighlightInfo.newHighlightInfo(HighlightInfoType.INFORMATION)
                .range(link.getTextRange())
                .textAttributes(CodeInsightColors.INACTIVE_HYPERLINK_ATTRIBUTES)
                .severity(HighlightSeverity.TEXT_ATTRIBUTES);

            String tooltip = link.getHighlightingInfo().getTooltip();
            if (tooltip != null) {
                String shortcutsText = HyperlinkAnnotator.getGoToDeclarationShortcutsText();
                info.descriptionAndTooltip(!shortcutsText.isEmpty() ? tooltip + " (" + shortcutsText + ")" : tooltip);
            }
            result.add(info.createUnconditionally());
        }
    }

    /**
     * Snapshot of the current cache generation for {@code file}. Pass this back to {@link #reportErrorsToWolf} together with
     * highlights collected from the cache so that a stale snapshot can't overwrite a newer Wolf write.
     */
    public long currentGeneration(VirtualFile file) {
        AtomicLong counter = myFileToCurrentGeneration.get(file);
        return counter != null ? counter.get() : 0L;
    }

    /**
     * @param observedGen the cache generation at the time {@code highlights} was collected. Reports with a strictly older
     *                    generation than the last committed one are dropped to prevent stale snapshots from racing newer ones.
     */
    public void reportErrorsToWolf(VirtualFile file, List<HighlightInfo> highlights, long observedGen) {
        boolean hasErrors = false;
        for (HighlightInfo info : highlights) {
            if (HighlightSeverity.ERROR.equals(info.getSeverity())) {
                hasErrors = true;
                break;
            }
        }

        ThreeState addClearNoAction;
        synchronized (myFilesWithWolfReportedErrors) {
            Long lastValue = myFileToLastWolfWrittenGen.get(file);
            long last = lastValue != null ? lastValue : -1L;
            if (observedGen < last) {
                addClearNoAction = ThreeState.UNSURE;
            }
            else {
                myFileToLastWolfWrittenGen.put(file, observedGen);
                if (hasErrors) {
                    myFilesWithWolfReportedErrors.add(file);
                    addClearNoAction = ThreeState.YES;
                }
                else if (myFilesWithWolfReportedErrors.remove(file)) {
                    addClearNoAction = ThreeState.NO;
                }
                else {
                    addClearNoAction = ThreeState.UNSURE;
                }
            }
        }

        // WolfTheProblemSolver may need read action to handle problems, do not call from synchronized block
        switch (addClearNoAction) {
            case YES -> reportProblemsFromExternalSource(file);
            case NO -> clearProblemsFromExternalSource(file);
            case UNSURE -> {
                /* do nothing */
            }
        }
    }

    /**
     * Consulo: {@code WolfTheProblemSolver.reportProblemsFromExternalSource(file, source)} does not exist in Consulo.
     * A single problem stands for the whole LSP error report of the file; {@code reportProblems} replaces the previous one.
     */
    private void reportProblemsFromExternalSource(VirtualFile file) {
        if (myProject.isDisposed()) {
            return;
        }
        WolfTheProblemSolver solver = WolfTheProblemSolver.getInstance(myProject);
        Problem problem = solver.convertToProblem(file, 0, 0, new String[]{LSP_EXTERNAL_SOURCE});
        if (problem != null) {
            solver.reportProblems(file, List.of(problem));
        }
    }

    /**
     * Consulo: {@code WolfTheProblemSolver.clearProblemsFromExternalSource(file, source)} does not exist in Consulo;
     * this clears all problems of the file.
     */
    private void clearProblemsFromExternalSource(VirtualFile file) {
        if (myProject.isDisposed()) {
            return;
        }
        WolfTheProblemSolver.getInstance(myProject).clearProblems(file);
    }

    public static HighlightInfo convertAnnotationToHighlightInfo(Annotation annotation) {
        HighlightInfoType type = toHighlightInfoType(annotation.getHighlightType(), annotation.getSeverity());
        HighlightInfo.Builder builder = HighlightInfo.newHighlightInfo(type)
            .range(annotation.getStartOffset(), annotation.getEndOffset())
            .severity(annotation.getSeverity());

        LocalizeValue message = annotation.getMessage();
        if (message.isNotEmpty()) {
            builder.description(message);
        }
        LocalizeValue tooltip = annotation.getTooltip();
        if (tooltip.isNotEmpty()) {
            builder.escapedToolTip(tooltip);
        }
        TextAttributes enforcedTextAttributes = annotation.getEnforcedTextAttributes();
        if (enforcedTextAttributes != null) {
            builder.textAttributes(enforcedTextAttributes);
        }
        else {
            builder.textAttributes(annotation.getTextAttributes());
        }
        if (annotation.isAfterEndOfLine()) {
            builder.endOfLine();
        }
        if (annotation.isFileLevelAnnotation()) {
            builder.fileLevelAnnotation();
        }
        if (annotation.getGutterIconRenderer() != null) {
            builder.gutterIconRenderer(annotation.getGutterIconRenderer());
        }

        List<Annotation.QuickFixInfo> quickFixes = annotation.getQuickFixes();
        if (quickFixes != null) {
            for (Annotation.QuickFixInfo fix : quickFixes) {
                builder.registerFix(fix.quickFix, null, LocalizeValue.empty(), fix.textRange, fix.key);
            }
        }

        return builder.createUnconditionally();
    }

    private static HighlightInfoType toHighlightInfoType(ProblemHighlightType problemHighlightType, HighlightSeverity severity) {
        // Consulo: IntelliJ's inspection-controlled UNUSED_SYMBOL/DEPRECATED/MARKED_FOR_REMOVAL types are the RAW_* ones here;
        // ProblemHighlightType.POSSIBLE_PROBLEM does not exist in Consulo.
        if (problemHighlightType == ProblemHighlightType.LIKE_UNUSED_SYMBOL) {
            return HighlightInfoType.RAW_UNUSED_SYMBOL;
        }
        if (problemHighlightType == ProblemHighlightType.LIKE_UNKNOWN_SYMBOL) {
            return HighlightInfoType.WRONG_REF;
        }
        if (problemHighlightType == ProblemHighlightType.LIKE_DEPRECATED) {
            return HighlightInfoType.RAW_DEPRECATED;
        }
        if (problemHighlightType == ProblemHighlightType.LIKE_MARKED_FOR_REMOVAL) {
            return HighlightInfoType.RAW_MARKED_FOR_REMOVAL;
        }
        return HighlightTypeUtil.convertSeverity(severity);
    }

    private static <R> @Nullable R readAction(Supplier<@Nullable R> computation) {
        return Application.get().runReadAction(computation);
    }
}

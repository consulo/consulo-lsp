# highlighting / highlightingCommon / inlayCommon / inlayHint / inlayHintColor

Scope: `plugin/src/main/java/com/intellij/platform/lsp/impl/features/{highlightingCommon,highlighting,inlayCommon,inlayHint,inlayHintColor}`.
All `.kt_` files of the scope are converted (none left).

## 1. module-info `requires` / pom (provided scope)

| JPMS module | maven artifactId | used for |
|---|---|---|
| `consulo.language.editor.impl` | `consulo-language-editor-impl` - **new** | `consulo.language.editor.impl.highlight.MainHighlightingPassFactory` / `HighlightInfoProcessor` (exported, non-internal package) |
| `consulo.language.editor.api` | (via `consulo-ide-api`) | passes, `HighlightInfo`, annotations, Wolf, highlight usages, declarative inlays, `CodeInsightLocalize` |
| `consulo.language.api`, `consulo.document.api`, `consulo.code.editor.api`, `consulo.file.editor.api`, `consulo.navigation.api`, `consulo.virtual.file.system.api`, `consulo.project.api`, `consulo.application.api` | (via `consulo-ide-api`) | |
| `consulo.ui.api` (`ImageEffects`, `RGBColor`, `Canvas2D`), `consulo.ui.ex.api` (`KeymapManager`, `KeymapUtil`, `IdeActions`) | (via `consulo-ide-api`) | color swatch icon, go-to-declaration shortcut text |
| `consulo.util.lang`, `consulo.util.collection`, `consulo.util.dataholder`, `consulo.localize.api`, `consulo.logging.api`, `consulo.annotation` (`org.jetbrains.annotations.Contract`) | (via `consulo-ide-api`) | |
| `jakarta.inject` | (via `consulo-ide-api`) | services/extensions |

## 2. `opens`

None (no gson/lsp4j reflection on my classes).

## 3. Missing Consulo APIs

- `com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl` / `AnnotationSessionImpl` - Consulo's holder is internal -> ported a minimal holder (list of `Annotation`, own `AnnotationBuilder`/`FixBuilder`, tooltip defaulting like Consulo's builder) to `plugin/.../consulo/lsp/impl/compat/AnnotationHolderImpl.java`; session = `new AnnotationSession(psiFile)`.
- `com.intellij.codeInsight.highlighting.HyperlinkAnnotator.getGoToDeclarationShortcutsText` - Consulo's is internal -> ported to `plugin/.../consulo/lsp/impl/compat/HyperlinkAnnotator.java`.
- `com.intellij.codeInsight.daemon.impl.BackgroundUpdateHighlightersUtil` - missing -> `LspHighlightingPass` collects in `doCollectInformation` and applies with `UpdateHighlightersUtil.setHighlightersToEditor(..., getColorsScheme(), GROUP_ID)` in `doApplyInformationToEditor` (EDT).
- `com.intellij.codeInsight.daemon.impl.HighlightInfoType.UNUSED_SYMBOL/DEPRECATED/MARKED_FOR_REMOVAL/POSSIBLE_PROBLEM` - mapped to `RAW_UNUSED_SYMBOL`/`RAW_DEPRECATED`/`RAW_MARKED_FOR_REMOVAL`; `ProblemHighlightType.POSSIBLE_PROBLEM` does not exist in Consulo (dropped). `HighlightInfo.convertSeverity` -> `HighlightTypeUtil.convertSeverity`. Annotation quick fixes -> `builder.registerFix(fix, null, LocalizeValue.empty(), range, key)`.
- `com.intellij.problems.WolfTheProblemSolver.reportProblemsFromExternalSource/clearProblemsFromExternalSource` - missing -> report: `reportProblems(file, List.of(convertToProblem(file, 0, 0, {"LSP"})))` (replaces the file's problems, idempotent); clear: `clearProblems(file)` (clears all problems of the file, not only LSP ones).
- `problemFileHighlightFilter` (`Condition<VirtualFile>`) -> `WolfFileProblemFilter` extension.
- `com.intellij.codeHighlighting.TextEditorHighlightingPassFactoryRegistrar` -> `TextEditorHighlightingPassFactory.register(Registrar)`.
- `com.intellij.openapi.editor.impl.DocumentImpl(text, true)` (notebook cell document in semantic tokens) -> `EditorFactory.getInstance().createDocument(text)`.
- `com.intellij.ide.IdeBundle "open.url.in.browser.tooltip"` -> `CodeInsightLocalize.openUrlInBrowserTooltip().get()`.
- `com.intellij.util.containers.Java11Shim.createConcurrentWeakMap` -> `ContainerUtil.createConcurrentWeakMap()`; `ContainerUtil.createWeakSet` -> `Collections.newSetFromMap(new WeakHashMap<>())`.
- Legacy IntelliJ inlay API (`InlayHintsProvider`, `InlayHintsProviderFactory`, `PresentationFactory`, `PresentationRenderer`, `SequencePresentation`, `ScaleAwarePresentationFactory`, `InlayContentListener`, `InlayHintsSettings`) - not public in Consulo -> mapped to Consulo's **declarative** inlay API:
  - `LspInlayHintsProviderFactory implements DeclarativeInlayHintsProviderFactory` (`@ExtensionImpl`): offers `LspInlayHintsProvider.INSTANCE` for every language; `getSupportedLanguages()` is empty so it is not listed in the settings (upstream `isVisibleInSettings = false`).
  - `LspInlayHintsProvider implements DeclarativeInlayHintsProvider` (not an extension, `getLanguage()` = `Language.ANY`, id `lsp.inlay.hints`); its `OwnBypassCollector` adds `LspInlayApplier.collectInlays(file)` to the sink. Reading the caches triggers the stale-cache requests, as upstream.
  - `LspInlayItem.buildPresentation(Editor, DeclarativeInlayTreeSink)` replaces `buildPresentation(Editor, PresentationFactory): InlayPresentation` (`getOffset()`/`getIdentity()` kept).
  - Inlay hints: `HintFormat.DEFAULT` (rounded background = upstream `roundWithBackground`), text parts via the tree builder; a part with `location` gets `InlayActionData(StringInlayActionPayload("line:char:uri"), "lsp.inlay.hint.navigation")`, handled by the new `LspInlayHintNavigationHandler` (`@ExtensionImpl InlayActionHandler`) which finds the client descriptor among the clients that have the editor's file open and calls `LspInlayHintRenderingKt.navigateTo` (`FileEditorManager.openTextEditor` + caret move).
  - Color swatches: 12x12 rounded icon drawn with `ImageEffects.canvas` (fill = color, stroke = `EditorColors.INDENT_GUIDE_COLOR`), `HintColorKind.TextWithoutBackground`, tooltip passed to the sink. Not scaled by the editor font size (upstream `JBUIScale.getFontScale(editorFontSize)`).
  - `LspInlayApplier.scheduleRefresh(file)` (generation-deduped, serialized executor) no longer paints out of band: it makes the declarative pass re-run. The pass factory skips an editor whose PSI modification count is unchanged and `DeclarativeInlayHintsPassFactory.scheduleRecompute/resetModificationStamp` is in `consulo.ide.impl` -> `WriteAction(PsiModificationTracker.incCounter())` + `DaemonCodeAnalyzer.restart(psiFile)`, only when an editor shows the file. **Platform improvement:** a public API to reschedule the declarative inlay pass for an editor/file would remove the counter bump.
  - Global inlay switch: the declarative pass does not run providers while hints are disabled globally, so the upstream `InlayHintsSettings.hintsEnabledGlobally()` check and `INLAY_SETTINGS_CHANGED` listener are dropped.
- Coroutines: `LspHighlightingCache` pipeline (`settleRequestStamp` loop with `delay`, `sendRequest`, `responseReceived`, `markSnapshotFresh`) is a `CompletableFuture` chain on `AppExecutorUtil` executors (delay -> `getAppScheduledExecutorService().schedule`). The coroutine `Job` is a private `RequestJob` (cancel flag + the future returned by `sendRequest`, which is cancelled; `ensureActive()` -> throws `CancellationException`). Note: cancelling the future returned by `sendRequest` only reaches the server request if the implementation propagates it (the composed futures of `forEachDocumentInFile` do not; `LspPullDiagnosticsCache` does for its timeout). `LspCoroutineScopeService` is not used. Serialized dispatchers -> `AppExecutorUtil.createBoundedApplicationPoolExecutor(name, 1)`; `withContext(Dispatchers.EDT)` -> `Application.invokeLater(..., project.getDisposed())`. `withTimeoutOrNull(60s)` -> `orTimeout` + cancel + `Failed`.

## 4. Registrations

| upstream xml | Consulo |
|---|---|
| `highlightingPassFactory implementation="...LspHighlightingPassFactory"` (+ `MainHighlightingPassFactory`) | `@ExtensionImpl LspHighlightingPassFactory implements MainHighlightingPassFactory, DumbAware` (project-level `TextEditorHighlightingPassFactory`); `register(Registrar)` sets `LspHighlightingApplier.GROUP_ID` |
| `highlightUsagesHandlerFactory` | `@ExtensionImpl LspHighlightUsagesHandlerFactory implements HighlightUsagesHandlerFactory<PsiElement>` |
| `problemFileHighlightFilter` | `@ExtensionImpl LspProblemFileHighlightFilter implements WolfFileProblemFilter` (`@Inject Project`) |
| `codeInsight.inlayProviderFactory` (`LspInlayHintsProviderFactory`) | `@ExtensionImpl LspInlayHintsProviderFactory implements DeclarativeInlayHintsProviderFactory` |
| `@Service(PROJECT) LspHighlightingApplier`, `LspInlayApplier` | `@ServiceAPI(PROJECT) @ServiceImpl @Singleton` on the class, `@Inject (Project)`, `getInstance(project)` = `project.getInstance(...)` |
| (new) | `@ExtensionImpl LspInlayHintNavigationHandler implements InlayActionHandler` (id `lsp.inlay.hint.navigation`) |

## 5. Files left as `.kt_`, TODOs

None left. TODOs in `LspHighlightingCacheRegistry`: `foldingRangeCache` (folding stage), `codeLensCache` (codeLens stage), `inheritanceMarkersCache` (lineMarkers stage) are not registered; add `register(new LspFoldingRangeCache(lspClient))` etc. + getters `getFoldingRangeCache()`, `getCodeLensCache()`, `getInheritanceMarkersCache()` when those stages land.

## 6. Java signatures exposed (where the rules leave a choice)

- Kotlin `Pair<Range, T>` = `consulo.util.lang.Pair<Range, T>` (as `LspDocumentMapping.aggregateToPullResult` already uses).
- `LspPullResult<T>`: sealed interface; `new LspPullResult.Full<>(items[, @Nullable Runnable onAccepted])` (`getItems()`, `getOnAccepted()`), `new LspPullResult.Unchanged<>([onAccepted])`, `LspPullResult.Failed.INSTANCE` (raw, assignable to any `LspPullResult<T>`).
- `LspHighlightingCache<T>`: `protected abstract CompletableFuture<LspPullResult<T>> sendRequest(VirtualFile)` (called on a pooled thread, no read action), `protected abstract CompletableFuture<@Nullable Void> onResponseReceived(VirtualFile)`, `protected Duration getQuiescenceDelay()` (`java.time.Duration`; constants `DIAGNOSTICS_QUIESCENCE_DELAY`, `LOW_PRIORITY_QUIESCENCE_DELAY`; static `get/setQuiescenceDelayOverride`), `public boolean getSupportsPull()`, `public List<LspCachedHighlighting<T>> getHighlightings(VirtualFile)`, `fileEdited`, `fileClosed`, `forceFullRepull`, `invalidate`, `clearCache`, `protected clearAdditionalCache()/(VirtualFile)`, `protected buildHighlightings(Document, List<Pair<Range,T>>)`, `protected applyServerHighlightings(file, long stamp, highlightings[, @Nullable Runnable onAccepted])`; field `protected final Project myProject` + `getProject()`.
- `LspCachedHighlighting<T>` (`getTextRange()`, `getHighlightingInfo()`), static `LspCachedHighlighting.applyPendingEdits(list, edits)`; `PendingEdit` own file.
- `LspHighlightingCacheRegistry`: `getPublishDiagnosticsCache()`, `getPullDiagnosticsCache()`, `getSemanticTokensCache()`, `getDocumentLinkCache()`, `getInlayHintsCache()`, `getDocumentColorCache()`, `fileEdited`, `clearCache`, `fileClosed`, `invalidatePulledResults`, `getDiagnosticsAndQuickFixes(VirtualFile)`.
- `LspHighlightingApplier`: `scheduleHighlightingRefresh(file)`, `scheduleHighlightingRefreshDebounced(file)`, `collectHighlightInfos(psiFile, file, document)`, `currentGeneration(file)`, `reportErrorsToWolf(file, infos, gen)`, static `convertAnnotationToHighlightInfo(Annotation)`, `public static volatile int GROUP_ID`, `UNREGISTERED_PASS_ID`, `EDIT_DEBOUNCE`, static `get/setEditDebounceOverride`, `get/setAfterCollectHook(Consumer<VirtualFile>)` (upstream suspend hook).
- `LspHighlightingPassFactory.PSI_MODIFICATION_STAMP` (top-level property of `LspHighlightingPassFactory.kt`).
- `LspDiagnosticAndLazyQuickFixes.copyDiagnosticWithRange(Diagnostic, Range)` (static; top-level fun of that file); `DiagnosticAndQuickFixes.build(...)` static; `LspDocumentLink` own file (`resolveDocumentLink(LspClient)`, `getTooltip()`, `getTargetUri()`).
- `LspInlayApplier`: `scheduleRefresh(file)`, `onFileClosed(file)`, `collectInlays(file)` (public: used by `LspInlayHintsProvider`).
- `LspInlayHintRenderingKt.collectInlayHintItems(project, file)`, `LspColorInlayRenderingKt.collectColorInlayItems(project, file)`.
- `LspDocumentHighlightCache(Project)` uses `LspPerFileCache(project, false, Matcher)`.
- Calls into not-yet-converted code by rule-derived names: `LspClientImpl.getDescriptor()/getProject()/getServerCapabilities()/getDocumentMapping()/getHighlightingCacheRegistry()/getRequestExecutor()/getDiagnosticsAndQuickFixes(file)/isFileOpened(file)/getDocumentVersion(doc)/logDebug(String)/logWarn(String)/notifyDiagnosticsReceived(file)/notifyDocumentLinksReceived(file)/sendRequest(Function<Lsp4jServer, CompletableFuture<R>>) -> CompletableFuture<R>`; `LspClient.sendRequestSync(LspClient.DEFAULT_REQUEST_TIMEOUT_MS, Function)`; `LspClientManagerImpl.getInstanceImpl(project).getClientsWithThisFileOpen(file)` (`Collection<LspClientImpl>`); `LspRequestExecutor.getDocumentHighlightsCaching(file, offset)` (`@Nullable List<TextRangeAndHighlightKind>`); `new LspQuickFixSet(client, file, diagnostic, @Nullable documentId).getQuickFixes()` (`List<IntentionAction>`).

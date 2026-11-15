# Folding / select word / code lens / document links / workspace symbol / breadcrumbs / Node.js runtime

Scope: `features/folding/*`, `features/selectWord/*`, `features/codeLens/*`, `features/navigation/LspDocumentLinkReferenceProvider`,
`features/workspaceSymbol/*`, `features/documentSymbol/LspCrumb`, `nodeRuntime/*`, plus the compat class
`consulo.lsp.impl.compat.Decompressor`. Touched outside the scope (as allowed): `LspHighlightingCacheRegistry` (folding / code
lens caches), `LspFeaturesRefreshing.refreshCodeLenses`, `LspRequestExecutor` (private `toWorkspaceSymbol` copy replaced by
`features.workspaceSymbol.ConvertersKt.toWorkspaceSymbol`), the Javadoc of `LspNavigationSymbol` (now lists its implementations).

Checked with a plain `javac -proc:none` against `plugin/target/classes` and the local `~/.m2` Consulo jars (no maven): the only
error is the missing `LspLocalize.lspSelectionRangeProgressTitle()` (new key below).

## 1. module-info `requires` / pom artifacts (provided)

| JPMS module | artifactId | used for |
|---|---|---|
| `consulo.search.everywhere.api` | `consulo-search-everywhere-api` | `SEResultsEqualityProvider`, `SearchEverywhereFoundElementInfo` |
| `consulo.http.api` | `consulo-http-api` | `HttpRequests` (Node.js download) |
| `consulo.platform.api` | `consulo-platform-api` | `Platform`, `CpuArchitecture`, `PlatformOperatingSystem` (probably already transitive) |
| `consulo.execution.debug.api` | `consulo-execution-debug-api` | `ExecutionDebugIconGroup.actionStartdebugger()` (code lens `$(debug)` icon) |

Already required and used: `consulo.language.editor.api` (code vision, folding, `ExtendWordSelectionHandler`), `consulo.ide.api`
(`GotoSymbolContributor`, `GotoClassOrTypeContributor`, `ChooseByNameContributorEx`), `consulo.process.api` (`GeneralCommandLine`,
`CapturingProcessUtil`), `consulo.container.api` (`ContainerPathManager`), `consulo.web.browser.api` (`BrowserUtil`),
`consulo.ui.ex.api` (`TargetAWT`), `consulo.util.io` (`DigestUtil`, `FileUtil`).

`exports`: if other plugins should use the Node.js runtime (upstream `LspNodeRuntimeManager`/`LspNodeRuntime`/`withNodeRuntimeEnsured`
are `@ApiStatus.Internal @Experimental` public API for integrations), `com.intellij.platform.lsp.impl.nodeRuntime` has to be exported.
Not done (module-info is not mine).

`opens`: none.

## 2. Localize keys to add

| key | text | used by |
|---|---|---|
| `lsp.selection.range.progress.title` | `Requesting selection ranges…` | `LspWordSelectionHandler` (modal progress on the UI thread, see 4) |

Existing keys used: `codelens.lspcodevisionprovider.name`/`.description`, `lsp.runtime.node.progress.title`/`.downloading`/`.verifying`/`.extracting`,
`lsp.resolving.reference.progress.title`.

## 3. Missing Consulo APIs (`Upstream FQN` - action)

- `com.intellij.lang.folding.FoldingBuilderEx` `language=""` - `consulo.language.editor.folding.FoldingBuilderEx`, `getLanguage()` = `Language.ANY`.
  **Platform change needed:** `FoldingBuilder.KEY` is built with `LanguageOneToMany.build(false)` (same as `ParameterInfoHandler`), so a
  builder for `Language.ANY` is never returned by `FoldingBuilder.forLanguage(Composite)`; **LSP folding does not work** until it is `build(true)`.
- `CodeFoldingSettings.COLLAPSE_CUSTOM_FOLDING_REGIONS` - not in Consulo; `FoldingRangeKind.Region` gets `collapsedByDefault = null` (IntelliJ default is `false`).
- `CodeFoldingManager.scheduleAsyncFoldingUpdate(editor)` - not in Consulo. The folding pass caches its result per editor with the
  dependencies PSI file + folding model + descriptor dependencies, so `LspFoldingRangeCache` has a Consulo-only
  `getModificationTracker()` (`SimpleModificationTracker`, bumped in `onResponseReceived`) that is added as a dependency of every
  `LspFoldingDescriptor`, and `onResponseReceived` restarts the daemon for the file (if an editor shows it). Edge case: a cached result
  with **no** LSP descriptors has no such dependency, so a server-pushed change from "no ranges" to "some ranges" without an edit shows up
  only after the next edit (the first response after opening a file is fine: the first folding pass is not cached).
- `com.intellij.lang.folding.CompositeFoldingBuilder` - `consulo.language.editor.internal.CompositeFoldingBuilder` (comments only).
- `com.intellij.codeInsight.editorActions.ExtendWordSelectionHandler` - `consulo.language.editor.action.ExtendWordSelectionHandler`.
  Consulo's `SelectWordHandler` calls `select()` on the **UI thread** (upstream: background read action, IJPL-115548), so
  `LspWordSelectionHandler.computeOffUiThread` runs `getSelectionRangeCaching` in a background read action under a cancellable modal
  progress (same pattern as `LspImplicitReferenceProvider`); off the UI thread it is called directly.
- `com.intellij.codeInsight.codeVision.CodeVisionProvider` / `CodeVisionHost` / `CodeVisionState` - Consulo has only
  `consulo.language.editor.codeVision.DaemonBoundCodeVisionProvider` (computed by the code vision highlighting pass in a background
  read action). `LspCodeVisionProvider implements DaemonBoundCodeVisionProvider`: `computeCodeVision` -> `computeForEditor(editor, psiFile)`
  returning the entries; `precomputeOnUiThread`, `singleEntryPerLine` dropped; `name` -> `getName(): LocalizeValue`.
  `LSP_CODE_VISION_PROVIDER_ID` -> `LspCodeVisionProvider.LSP_CODE_VISION_PROVIDER_ID`.
- `ClickableTextCodeVisionEntry` click handler `(MouseEvent?, Editor)` - Consulo passes a `consulo.ui.event.ComponentEvent`; it is
  converted back to an AWT `MouseEvent` (`MouseInputDetails` position/modifiers/button + `TargetAWT.to(component)`) for
  `LspCodeLensSupport.codeLensClicked`; a non-mouse invocation (more popup, shortcut) passes `null`, like upstream for keyboard.
- `CodeVisionHost.invalidateProvider(LensInvalidateSignal(null, listOf(id)))` (`LspFeaturesRefreshing.refreshCodeLenses`) - no API;
  the code vision pass is skipped while the PSI modification count is unchanged (`CodeVisionPassFactory.resetModificationStamp` is in
  `consulo.ide.impl`), so `refreshCodeLenses` increments `PsiModificationTracker` (write action) and restarts the daemon for every
  file shown in an editor of the project (same trick as `LspInlayApplier.scheduleRefresh`).
- `com.intellij.codeInsight.codeVision.settings.CodeVisionGroupSettingProvider` (`config.codeVisionGroupSettingProvider`) - no EP
  (Consulo's is in `consulo.ide.impl`); Consulo's settings group code vision providers by `getGroupId()` and show `getName()`.
  `LspCodeVisionGroupSettingProvider` is kept as an unregistered plain class (`getGroupId()`, `getGroupName()`, `getDescription()`).
- `AllIcons.Actions.Execute` / `AllIcons.Actions.StartDebugger` (codicons) - `PlatformIconGroup.actionsExecute()` /
  `ExecutionDebugIconGroup.actionStartdebugger()`; `CodeLensTitle.getIcon()` is a `consulo.ui.image.Image` (converted with
  `TargetAWT.to` for the entry, whose icon is a Swing `Icon`). `parseCodeLensTitle` -> static `CodeLensTitle.parseCodeLensTitle`.
- `com.intellij.model.psi.ImplicitReferenceProvider` (document links) - `LspDocumentLinkReferenceProvider implements GotoDeclarationHandler`
  (`@ExtensionImpl(id = "LspDocumentLinkReferenceProvider")`), like `LspImplicitReferenceProvider` in navigation.md:
  `getImplicitReference(PsiFile, int)` returns the nested holder `LspDocumentLinkSymbolReference` (upstream private `PsiSymbolReference`);
  on Ctrl+hover (background thread) the link is only returned when the PSI leaf fits the link range (Consulo underlines the leaf);
  on a performed action (UI thread) `resolveReference()` (which may send `documentLink/resolve`) runs under a modal progress
  (`lsp.resolving.reference.progress.title`).
- `Symbol`/`NavigatableSymbol`/`NavigationTarget`/`TargetPresentation`/`Pointer` - `LspPathDocumentLinkSymbol` and
  `LspOpenBrowserNavigatableSymbol` are `FakePsiElement`s implementing `LspNavigationSymbol` that navigate themselves
  (`getNavigationTargets(Project)` returns `List<Navigatable>`); `computeLinkPresentation` -> `getName/getPresentableText/getLocationString/getIcon`.
  The private `LspDocumentLinkNavigationTarget` is nested in `LspPathDocumentLinkSymbol`. `NavigationRequest.directoryNavigationRequest`
  -> `PsiDirectory.navigate`, `sourceNavigationRequest(project, file, 0)` -> `OpenFileDescriptor` at offset 0.
  `LspOpenBrowserNavigatableSymbol` takes an extra `Project` (a PSI element needs one); `computeDocumentationHint()` kept but not asked
  by Consulo's Ctrl+hover. `AllIcons.General.Web` -> `PlatformIconGroup.generalWeb()`. `createPointer()` dropped.
- Handler order: Consulo uses the first `GotoDeclarationHandler` with targets. Upstream registers the document link provider after
  `LspImplicitReferenceProvider`; an `order = "after ..."` needs an `id` on `LspImplicitReferenceProvider` (not in my scope) - the order of
  the two is unspecified now (they only collide when a definition and a link are at the same offset).
  Suggested: `@ExtensionImpl(id = "LspImplicitReferenceProvider")` there and `order = "after LspImplicitReferenceProvider"` here.
- `com.intellij.navigation.ChooseByNameContributorEx2` - Consulo's `ChooseByNameContributorEx` already has
  `processNames(Processor, FindSymbolParameters)`. `isAvailableNow(Project)` is not in Consulo, kept as a plain method (unused).
  `gotoClassContributor` -> `consulo.ide.navigation.GotoClassOrTypeContributor`, `gotoSymbolContributor` -> `GotoSymbolContributor`.
  `runBlockingCancellable { clients.map { async { ... } }.awaitAll() }` -> a plain loop with `ProgressManager.checkCanceled()` (the
  requests are blocking, so upstream runs them one after another too).
- `com.intellij.ide.actions.searcheverywhere.AbstractEqualityProvider` / `PSIPresentationBgRendererWrapper` - not in Consulo;
  `LspWorkspaceSymbolEqualityProvider implements SEResultsEqualityProvider` (`compareItems(newItem, alreadyFoundItem)` = upstream
  `AbstractEqualityProvider` logic for one item, REPLACE when the new item has a higher priority, else SKIP), elements are compared
  directly (no presentation wrapper in Consulo). Consulo's `TrivialElementsEqualityProvider` already does the same for `equals` elements.
- `com.intellij.codeInsight.breadcrumbs.FileBreadcrumbsCollector` / `Crumb` / `BreadcrumbsXmlWrapper.FORCE_RELOAD_BREADCRUMBS` - re-checked
  in the Consulo sources: no editor breadcrumbs at all (only the `consulo.ui.Breadcrumbs` UI component; sticky lines come from
  `code-editor-impl` / `language-editor-impl` internal passes based on the structure view). **`LspCrumb.kt_` stays unported**,
  `LspFileBreadcrumbsCollector.java` keeps only its static helpers, `refreshBreadcrumbs()` stays a no-op.
- Node.js runtime (`nodeRuntime/*`):
  - `@Service(APP)` `LspNodeRuntimeManager` -> `@ServiceAPI(APPLICATION) @ServiceImpl @Singleton` on the class; `@Service(PROJECT)`
    `LspNodeRuntimeDownloads` -> `@ServiceAPI(PROJECT) @ServiceImpl @Singleton`, `@Inject (Project)`.
  - `fun LspIntegrationProvider.withNodeRuntimeEnsured(project, startClient)` -> `LspNodeRuntimeDownloads.withNodeRuntimeEnsured(provider, project, Runnable)`.
  - top-level `managedPathValue`, `shellDirectories` -> static in `LspNodeRuntimeManager`; companion members -> static; `LspNodeRuntime`,
    `LspNodeHost` own files (getters `getVersion()`, `getRuntimeDir()`, `getNode()`, `getNpx()`, `getNpm()`, `getBinDir()`, `isWindows()`).
  - `PathManager.getSystemDir()` -> `ContainerPathManager.get().getSystemDir()`; `HttpRequests` -> `consulo.http.HttpRequests`
    (`request(url).userAgent(..).saveToFile(Path, indicator)`); `GeneralCommandLine` -> `consulo.process.cmd.GeneralCommandLine`;
    `CapturingProcessHandler(cl).runProcess(timeout)` -> `CapturingProcessUtil.execAndGetOutput(cl, timeout)`;
    `EnvironmentUtil.getValue` -> `Platform.current().os().getEnvironmentVariable`; `OS.CURRENT`/`CpuArch.CURRENT` ->
    `Platform.current().os()` / `Platform.current().jvm().arch()` (`LspNodeHost.of(PlatformOperatingSystem, CpuArchitecture)`);
    `VersionComparatorUtil` -> `consulo.util.lang.VersionComparatorUtil`; `sha256Hex(Path)` -> private helper on `DigestUtil.sha256()`;
    `NioFiles.deleteRecursively` -> private helper on `FileUtil.delete(Path)` that throws on failure.
  - `com.intellij.util.io.Decompressor` - no public equivalent (the platform's commons-compress is not visible to plugins) ->
    minimal compat copy `consulo.lsp.impl.compat.Decompressor` (`Zip`, `Tar` for `.tar.gz`, `removePrefixPath`, `extract`): JDK zip +
    own tar reader (ustar prefix, GNU `L`/`K` long names, pax `path`/`linkpath`/`size`, files/dirs/symlinks/hard links, POSIX mode,
    zip-slip and outside-symlink checks).
  - `ProcessCanceledException` is not a `CancellationException` in Consulo, so the "rethrow cancellation" catches take both.

## 4. Registration mapping

| upstream xml | Consulo |
|---|---|
| `lang.foldingBuilder language=""` `LspFoldingBuilder` | `@ExtensionImpl FoldingBuilderEx`, `getLanguage()` = `Language.ANY` (needs the platform fix in 3) |
| `extendWordSelectionHandler id="LspWordSelectionHandler" order="last"` | `@ExtensionImpl(id = "LspWordSelectionHandler")` (`last` dropped) |
| `codeInsight.codeVisionProvider` `LspCodeVisionProvider` | `@ExtensionImpl DaemonBoundCodeVisionProvider` |
| `config.codeVisionGroupSettingProvider` | not registered (no EP) |
| `psi.implicitReferenceProvider id="LspDocumentLinkReferenceProvider"` | `@ExtensionImpl(id = "LspDocumentLinkReferenceProvider") GotoDeclarationHandler` |
| `gotoSymbolContributor` `LspGoToSymbolContributor` | `@ExtensionImpl GotoSymbolContributor` |
| `gotoClassContributor` `LspGoToClassContributor` | `@ExtensionImpl GotoClassOrTypeContributor` |
| `searchEverywhereResultsEqualityProvider` | `@ExtensionImpl SEResultsEqualityProvider` |
| `fileBreadcrumbsCollector` | not registered (no EP) |
| `@Service(APP) LspNodeRuntimeManager` | `@ServiceAPI(APPLICATION) @ServiceImpl @Singleton` |
| `@Service(PROJECT) LspNodeRuntimeDownloads` | `@ServiceAPI(PROJECT) @ServiceImpl @Singleton` |

## 5. Left as `.kt_` / not tested

- `features/documentSymbol/LspCrumb.kt_` - no breadcrumbs in Consulo (see 3).
- Nothing was run. Untested: folding (blocked by the `FoldingBuilder.KEY` platform issue anyway), code lens rendering and click
  conversion, document link Ctrl+click (URL / file / directory), Go To Symbol / Class with a server, Search Everywhere dedup,
  the whole Node.js download (HTTP, checksum, tar/zip extraction incl. symlinks `bin/npm`, `npx --version` check).

## 6. Java signatures exposed (beyond the rules)

- `LspHighlightingCacheRegistry.getFoldingRangeCache()`, `getCodeLensCache()`.
- `LspFoldingRangeCache.getModificationTracker(): ModificationTracker` (Consulo-only).
- `LspCodeVisionProvider.LSP_CODE_VISION_PROVIDER_ID`; `CodeLensTitle(String, @Nullable Image)`, `getText()`, `getIcon()`, static `parseCodeLensTitle(String)`.
- `com.intellij.platform.lsp.impl.features.workspaceSymbol.ConvertersKt.toWorkspaceSymbol(SymbolInformation)` (note: another
  `ConvertersKt` exists in `features.documentSymbol`; `LspRequestExecutor` uses the FQN).
- `LspDocumentLinkReferenceProvider.getImplicitReference(PsiFile, int)` -> `@Nullable LspDocumentLinkSymbolReference` (`getElement()`,
  `getRangeInElement()`, `resolvesTo(PsiElement)`, `resolveReference(): List<PsiElement>`).
- `LspPathDocumentLinkSymbol(Project, VirtualFile, List<VirtualFile> roots)`, `LspOpenBrowserNavigatableSymbol(Project, String url, @Nullable String tooltip)`.
- `LspWorkspaceSymbolContributor.isAvailableNow(Project)`, abstract `shouldAcceptSymbolKind(SymbolKind)`.
- `LspNodeRuntimeManager.getInstance()`, `getRuntime()`, `ensureRuntime()`, `ensureRuntime(@Nullable ProgressIndicator)`, statics
  `NODE_VERSION`, `managedPathValue(Path)`, `shellDirectories()`, `nodeEnvironmentVariables(Path)`, `runtimesDir()`, `archiveFileName`,
  `findNodeExecutable`, `stagingDirFor`, `cleanRuntimeDir`, `promoteStagedInstall`, `deleteOtherRuntimeVersions` (upstream `@VisibleForTesting internal`).
- `LspNodeRuntimeDownloads.getInstance(Project)`, `restartWhenRuntimeReady(Class)`, static `withNodeRuntimeEnsured(LspIntegrationProvider, Project, Runnable)`.
- `LspNodeRuntime.applyTo(GeneralCommandLine)`, `environmentVariables()`.

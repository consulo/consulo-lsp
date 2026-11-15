# Navigation / usages / document symbols / structure view / intentions

Scope: `features/navigation/*` (except `LspDynamicFiles`), `features/usages/*`, `features/documentSymbol/*`, `structureView/*`,
`features/intention/*`, `features/quickFix/*`, plus the compat classes `consulo.lsp.impl.compat.ActionContextElement*`.

## 1. module-info `requires` / pom artifacts (provided)

| JPMS module | artifactId | used for |
|---|---|---|
| `consulo.ide.api` | `consulo-ide-api` | umbrella (most of the below come transitively, listed for safety) |
| `consulo.language.api` | `consulo-language-api` | PSI, `PomDeclarationSearcher`, `DefinitionsScopedSearch(Executor)`, `VirtualFileWindow` |
| `consulo.language.impl` | `consulo-language-impl` | `FakePsiElement` (`consulo.language.impl.psi`) |
| `consulo.language.editor.api` | `consulo-language-editor-api` | `GotoDeclarationHandler`, `TypeDeclarationProvider`, `TargetElementUtilExtender`, `IntentionAction`/`IntentionMetaData`/`IntentionManager`, `TextEditorBasedStructureViewModel`, `StructureViewFactoryEx`, `EditorWindow` |
| `consulo.language.ui.api` | `consulo-language-ui-api` | `NavBarModelExtension` |
| `consulo.file.editor.api` | `consulo-file-editor-api` | `StructureViewBuilderProvider`, `TreeBasedStructureViewBuilder`, `FileEditorManager` |
| `consulo.usage.api` | `consulo-usage-api` | `UsageTargetProvider`, `PsiElementUsageTargetFactory`, `UsageInfo` |
| `consulo.find.api` | `consulo-find-api` | `FindUsagesHandlerFactory`, `FindUsagesHandler`, `FindManager` |
| `consulo.ui.ex.api` | `consulo-ui-ex-api` | `AnActionListener`, `IdeActions`, `ActionManager`, `ColoredItemPresentation` |
| `consulo.project.api` | `consulo-project-api` | `PostStartupActivity`, `QueryExecutorBase` |
| `consulo.application.ui.api` | `consulo-application-ui-api` | `UISettings` |
| `consulo.code.editor.api`, `consulo.document.api`, `consulo.navigation.api`, `consulo.virtual.file.system.api`, `consulo.application.api`, `consulo.localize.api` | same names with dashes | editor, documents, `OpenFileDescriptorFactory`, `VirtualFileManager.getFileIcon`, `Registry`, `ReadAction`, `ProgressManager` |

## 2. `opens`

None needed (no gson/lsp4j reflection on these classes).

## 3. Missing Consulo APIs (`Upstream FQN` - action)

- `com.intellij.model.psi.ImplicitReferenceProvider` / `PsiSymbolReference` - `LspImplicitReferenceProvider` is a `GotoDeclarationHandler`; `LspResolvedSymbolReference` is a plain holder (nested public class). Ctrl+hover = called off the UI thread (Consulo's `CtrlMouseHandler` runs in a non-blocking read action); a performed action calls it on the UI thread, where the LSP request runs under a modal progress (`ProgressManager.runProcessWithProgressSynchronously`, title `LocalizeValue.localizeTODO("Resolving reference…")` - TODO: add an `LspLocalize` key).
- `com.intellij.model.Symbol`, `com.intellij.navigation.NavigatableSymbol`, `com.intellij.platform.backend.navigation.NavigationTarget`, `TargetPresentation`, `Pointer` - `LspNavigationSymbol` is an interface `extends PsiElement, Navigatable` (not sealed: document-link symbols are later stage); `LspDefinitionSymbol` is a `FakePsiElement` that navigates itself; presentation -> `ItemPresentation` methods.
- `com.intellij.codeInsight.navigation.SymbolTypeProvider` (`lang.symbolTypeProvider`) - `LspSymbolTypeProvider extends TypeDeclarationProvider`. Consulo calls type providers only for a target element found by `TargetElementUtil`, so a new Consulo-only `LspTypeDeclarationTargetElementUtilExtender` (`TargetElementUtilExtender.modifyReferenceOrReferencedElement`, only when no PSI reference and only while the `GotoTypeDeclaration` action runs) returns the first `typeDefinition` result as the target element; the provider then returns all of them (cache hit).
- `com.intellij.openapi.actionSystem.ex.ActionUtil.getActionThreadContext()` / `ActionContextElement` - ported as `consulo.lsp.impl.compat.ActionContextElement` (+ `ActionContextElementListener`, `@TopicImpl(APPLICATION) AnActionListener` that records the performed action id for the performing thread). Not propagated to other threads.
- `com.intellij.codeInsight.navigation.CtrlMouseActionElement` - dropped; Ctrl+hover is detected by thread (see above).
- `com.intellij.find.usages.api.SearchTarget`/`UsageHandler`/`UsageSearcher`/`PsiUsage`, `UiDataRule` + `FindUsagesAction.SEARCH_TARGETS` - `LspSearchTarget` is a `FakePsiElement`; `LspSearchTargetsRule implements UsageTargetProvider` (`order="last"`) wraps it via `PsiElementUsageTargetFactory`; `LspUsageSearcher extends FindUsagesHandlerFactory` whose handler runs `LspReferencesQuery` and reports `UsageInfo(psiFile, start, end)`. Static `LspSearchTargetsRule.isFindReferencesEnabledFor(LspClientImpl, VirtualFile)`.
- Go To Declaration on a declaration (upstream falls back to Show Usages) - the handler wraps `LspSearchTarget` into a private `LspShowUsagesElement` whose `navigate()` calls `FindManager.findUsages(target, false)` (Find tool window; the Show Usages popup is not public API in Consulo).
- `com.intellij.ide.structureView` `lang.psiStructureViewFactory language=""` - Consulo has no any-language fallback for `PsiStructureViewFactory`; `LspStructureViewFactory implements StructureViewBuilderProvider` (`order="last"`), keeps `getStructureViewBuilder(PsiFile)`. Works for any file type incl. plain text.
- `StructureViewModel.isValid()` - does not exist; `LspStructureViewModel.getRoot()` recomputes the root when the PSI modification count changed.
- `com.intellij.ide.impl.StructureViewWrapperImpl.STRUCTURE_CHANGED` - no topic; `LspStructureViewProjectActivity` calls `StructureViewFactoryEx.getStructureViewWrapper().selectCurrentElement(selectedEditor, file, false)`, which rebuilds when the view had no structure for that editor yet. A view that already exists for the same editor is not rebuilt on server shutdown.
- `com.intellij.openapi.startup.ProjectActivity` - `PostStartupActivity` (+ `DumbAware`).
- `com.intellij.codeInsight.breadcrumbs.FileBreadcrumbsCollector`, `Crumb`, `NavigatableCrumb`, `StickyLineInfo` - no editor breadcrumbs EP in Consulo (sticky lines come only from `PsiStructureViewFactory`). `LspFileBreadcrumbsCollector.java` keeps only the static helpers `findBreadcrumbsLspClient`, `findSymbolsAroundOffset`; the collector and `LspCrumb.kt_` are not ported.
- `com.intellij.ide.navigationToolbar.AbstractNavBarModelExtension` - `consulo.language.ui.navigationBar.NavBarModelExtension`. `NavigationRequests` -> `navigate()` with `OpenFileDescriptorFactory`. `isEditorBreadcrumbsReplacedByNavBar()` kept as static (unused, breadcrumbs not ported). Registry key read with `Registry.is("lsp.navbar.members.hide.editor.breadcrumbs", true)`.
- `IntentionAction.getFamilyName()`, `generatePreview()`/`IntentionPreviewInfo` - not in Consulo. `getFamilyName()` kept as plain method returning `LocalizeValue`; preview dropped.
- `IconUtil.computeFileIcon` / `IconUtil.getIcon(file, flags, project)` - `VirtualFileManager.getInstance().getFileIcon(file, project, flags)`.
- `VfsUtilCore.getRelativePath` - `VirtualFileUtil.getRelativePath`.
- `PsiNavigationSupport.createNavigatable` / `OpenFileDescriptor` - `OpenFileDescriptorFactory.getInstance(project).newBuilder(file).offset(o).build()`.
- `DefinitionsScopedSearch.SearchParameters.project` - not in Consulo; the project is taken from the target's PSI file.
- `IdeActions.ACTION_GOTO_DECLARATION_ONLY` - no such action in Consulo; constant `LspImplicitReferenceProvider.ACTION_GOTO_DECLARATION_ONLY`.

## 4. Registration mapping

| upstream xml | Consulo |
|---|---|
| `psi.implicitReferenceProvider` `LspImplicitReferenceProvider` | `@ExtensionImpl GotoDeclarationHandler` |
| `lang.symbolTypeProvider id="LspSymbolTypeProvider"` | `@ExtensionImpl TypeDeclarationProvider` + `@ExtensionImpl LspTypeDeclarationTargetElementUtilExtender` (new) |
| `pom.declarationSearcher` `LspImplementationDeclarationSearcher` | `@ExtensionImpl PomDeclarationSearcher` (gated by `ActionContextElement` = `GotoImplementation`) |
| `definitionsScopedSearch` `LspDefinitionsScopedSearcher` | `@ExtensionImpl QueryExecutorBase implements DefinitionsScopedSearchExecutor` |
| `uiDataRule` `LspSearchTargetsRule` | `@ExtensionImpl(order="last") UsageTargetProvider` |
| `searcher forClass=UsageSearchParameters` `LspUsageSearcher` | `@ExtensionImpl FindUsagesHandlerFactory` (project scope) |
| `fileBreadcrumbsCollector` | not registered (no EP) |
| `navbar` `LspNavBarModelExtension` | `@ExtensionImpl NavBarModelExtension` |
| `lang.psiStructureViewFactory language=""` | `@ExtensionImpl(order="last") StructureViewBuilderProvider` |
| `postStartupActivity` `LspStructureViewProjectActivity` | `@ExtensionImpl PostStartupActivity` |
| `intentionAction` x15 `LspIntention0..14` | `@ExtensionImpl @IntentionMetaData(ignoreId="platform.lsp.intention", categories="LSP", fileExtensions="txt")` on each. Consulo looks up the description by class simple name, so `resources/intentionDescriptions/LspIntention` was copied to `LspIntention0..14` (original dir kept). |
| `@Service(PROJECT) LspIntentionActionService` | `@ServiceAPI(PROJECT) @ServiceImpl @Singleton`, `@Inject (Project)`, `Disposable` |
| `registryKey lsp.navbar.members.hide.editor.breadcrumbs` | `Registry.is(key, true)` |
| (new) | `@TopicImpl(APPLICATION) consulo.lsp.impl.compat.ActionContextElementListener` |

## 5. Left as `.kt_` / TODOs

- `features/navigation/LspDocumentLinkReferenceProvider.kt_` - later stage (document links).
- `features/documentSymbol/LspCrumb.kt_` - no breadcrumbs in Consulo.
- `features/codeActions/LspRefactoringCodeActions.kt_` - only used by later-stage extract/inline, not needed by intentions.
- TODO: `LspLocalize` key for the "Resolving reference…" progress title (currently `localizeTODO`).
- Known limitations: Ctrl+hover underlines the PSI leaf (Consulo `CtrlMouseHandler`), so the LSP underline is shown only when the leaf fits into the server's origin range (never in flat plain-text PSI; Ctrl+click still works). Ctrl+Shift+hover (type declaration) has no LSP support. `FindUsagesAction`/`ShowUsagesAction` are disabled in an editor whose language has no `FindUsagesProvider` (plain text) - Consulo's `FindUsagesInFileAction.isEnabled`; Go To Declaration on a declaration still opens Find Usages. LSP usage targets come before the PSI-element targets (Consulo appends editor-based targets first).

## 6. Java signatures exposed (beyond the rules)

- `LspImplicitReferenceProvider.getImplicitReference(PsiFile, int offset, String actionId, boolean isCtrlHover)` -> `@Nullable LspImplicitReferenceProvider.LspResolvedSymbolReference` (upstream `getImplicitReference(element, offsetInElement)` with `currentRequest()` made explicit).
- `LspSearchTarget(Collection<LspClient>, VirtualFile, Position)` extends `FakePsiElement`; `getLspClients()`, `getFile()`, `getPosition()`, `getUsageHandlerPresentableText()`.
- `LspDefinitionSymbol(Project, VirtualFile, @Nullable Range)` extends `FakePsiElement`; `getNavigationTargets(Project)` returns `List<Navigatable>`.
- `LspIntentionActionWrapperBase`: `getLspIntentionAction()`/`setLspIntentionAction(...)`, `getIndex()`, `getFamilyName(): LocalizeValue`.
- `LspIntentionActionService.toCodeAction(Either<Command, CodeAction>)` (static; Kotlin extension in `LspIntentionActionService.kt`).
- `LspQuickFixSet(LspClientImpl, VirtualFile, Diagnostic[, @Nullable TextDocumentIdentifier])`, `getQuickFixes()`, `ensureInitialized()`.
- `LspStructureViewFactory.getStructureViewBuilder(PsiFile)` kept next to the provider method.
- Assumed API-module signatures (not converted yet when written): `LspSymbolKindCustomizer.getIcon(SymbolKind)` returns `@Nullable consulo.ui.image.Image`; `LspIntentionAction.getText()` returns `LocalizeValue`, `isAvailable(Project, Editor, PsiFile)`, `invoke(Project, Editor, PsiFile)`; `LspCodeActionsSupport.getIntentionActionsSupport()`, `createIntentionAction(LspClient, CodeAction)`, `createQuickFix(LspClient, CodeAction)`. Core: `LspClientImpl.getDynamicFiles().findTargetFile(String)`, `LspClientManagerImpl.getInstanceImpl(project).getClientsForFileRequests/getClientsWithThisFileOpen(file)`.

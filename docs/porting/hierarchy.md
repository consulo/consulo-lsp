# Call / type hierarchy, inheritance line markers

Scope: `features/hierarchy/**` (call + type hierarchy), `features/lineMarkers/**` (inheritance gutter markers), plus the
`inheritanceMarkersCache` registration in `features/highlightingCommon/LspHighlightingCacheRegistry` and the Javadoc link
in `features/LspFeaturesRefreshing` (`refreshLineMarkers` was already ported). `LspClientImpl` does not reference these classes.

## 1. module-info `requires` / pom artifacts (provided)

Nothing new; everything is in modules already required:

| JPMS module | used for |
|---|---|
| `consulo.language.editor.api` | `consulo.language.editor.hierarchy.*` (`HierarchyProvider`, `HierarchyModel`, `HierarchyNodeDescriptor`, `HierarchyTreeStructure`, `StandardHierarchyKinds`, `StandardHierarchyViewTypes`), `consulo.language.editor.gutter.*` (`LineMarkerProviderDescriptor`, `LineMarkerInfo`, `NavigateAction`, `GutterIconNavigationHandler`), `Pass` |
| `consulo.language.impl` | `FakePsiElement` |
| `consulo.ui.ex.api` | `CompositeAppearance`, `RelativePoint`, `IdeActions` |
| `consulo.ui.api` | `ComponentEvent` |
| `consulo.application.api` | `Task.Backgroundable`, `ReadAction`, `Registry`, `AppExecutorUtil`, `DumbAware` |
| `consulo.component.api` | `ProcessCanceledException` |
| base icon library (already used) | `PlatformIconGroup.gutterImplementedmethod()` / `gutterOverridenmethod()` |

No `opens` needed.

## 2. Localize keys

None to add. Used (already in the yaml): `linemarkers.lspinheritancelinemarkerprovider.name`, `linemarkers.tooltip.has.implementations`,
`linemarkers.tooltip.is.overridden`, `linemarkers.tooltip.has.subtypes`, `linemarkers.popup.title.implementations`,
`linemarkers.popup.title.overrides`, `linemarkers.popup.title.subtypes`.

## 3. Files

| Upstream | Java |
|---|---|
| `hierarchy/Lsp4jHierarchyItem.kt` (data class) | `Lsp4jHierarchyItem` (final, `getX()`, equals/hashCode/toString, static `from(CallHierarchyItem)`/`from(TypeHierarchyItem)`) |
| `hierarchy/LspFakePsiElement.kt` | `LspFakePsiElement extends FakePsiElement` (`getPsiFile()`, `getLsp4jPosition()`, ctor overload for default `name = ""`) |
| `hierarchy/LspHierarchyUtil.kt` | `LspHierarchyUtilKt` (`getTargetFromEditor`, `findSupportingClient`, predicates are `BiPredicate<LspClientImpl, VirtualFile>`) + Consulo-only `hasLanguageSpecificTarget` |
| `hierarchy/LspHierarchyNodeDescriptor.kt` | `LspHierarchyNodeDescriptor extends HierarchyNodeDescriptor implements Navigatable` (`getClient()`, `getItem()`, `isBase()`) |
| `hierarchy/LspAbstractHierarchyTreeStructure.kt` | same name, `extends HierarchyTreeStructure` |
| `hierarchy/call/*`, `hierarchy/type/*` | same names |
| `lineMarkers/LspInheritanceMarkersRequests.kt` | `LspInheritanceMarkersRequestsKt` (static, receiver `LspClientImpl` first; suspend -> `CompletableFuture`) + package-private `cancelOnCancellation` |
| `lineMarkers/LspInheritanceMarkersCache.kt` | same name |
| `lineMarkers/LspInheritanceLineMarkerProvider.kt` | `LspInheritanceLineMarkerProvider` + `LspInheritanceMarkerNavigationHandler` (own file, public top-level class) |

## 4. Missing Consulo APIs (`Upstream FQN` - action)

- `com.intellij.ide.hierarchy.HierarchyProvider` (`callHierarchyProvider` / `typeHierarchyProvider` EPs) - Consulo has one
  `consulo.language.editor.hierarchy.HierarchyProvider<E>` EP (redesigned 2026-09) with a `getKind()` (`StandardHierarchyKinds.CALL` / `TYPE`)
  and `createModel(project, target)` returning a `HierarchyModel`; the platform owns the browser/tool window/toolbar.
  `createHierarchyBrowser` -> `createModel`; `browserActivated` (switch to the caller / subtypes view) -> `HierarchyModel.getDefaultViewType()`.
- `com.intellij.ide.hierarchy.CallHierarchyBrowserBase` / `TypeHierarchyBrowserBase` / `HierarchyBrowserBaseEx` - no subclassable
  browser in Consulo. `LspCallHierarchyBrowser` / `LspTypeHierarchyBrowser` keep their names but implement `HierarchyModel<PsiElement>`:
  - `createTrees` -> `getViewTypes()` (`StandardHierarchyViewTypes.CALLER`/`CALLEE`, `SUPERTYPES`/`SUBTYPES`); `getCallerType()` etc. -> private static helpers returning those view types.
  - `createHierarchyTreeStructure` -> `createTreeStructure(HierarchyRequest)`; the type browser returns `null` for `StandardHierarchyViewTypes.CLASS` (upstream `getTypeHierarchyType()`), which is not offered anyway.
  - `isApplicableElement` -> same model method.
  - `getElementFromDescriptor` (`descriptor.psiElement`) - dropped; the platform browser uses `HierarchyNodeDescriptor.getHierarchyElement()` = `getPsiElement()`.
  - `getComparator` (alphabetical via `HierarchyBrowserManager.SORT_ALPHABETICALLY`, otherwise by index) - dropped; the platform browser does exactly that.
  - Call `prependActions` removed `ChangeScopeAction` - `HierarchyModel.isScopeSupported()` defaults to `false`.
  - Type `prependActions` (supertypes / subtypes / alpha sort actions), "Base on this type" bound to the `TypeHierarchy` shortcut - provided by the platform toolbar and `StandardHierarchyKinds.TYPE.getShortcutActionId()`.
  - `isInterface` = false, `canBeDeleted` = false, `getQualifiedName` = null, `createLegendPanel` = null - Consulo defaults (`HierarchyNodeDescriptor.canBeDeleted()`/`getQualifiedName()`, `HierarchyModel.getLegend()` empty); `isInterface` has no counterpart.
  - `createHierarchyTree`: `tree.setRootVisible(false)` and `DefaultTreeUI.AUTO_EXPAND_FILTER` (`skipAutoExpand`) - **not supported**: the
    platform tree always shows the root and only expands the base node. The invisible base descriptor (item = `null`) is therefore visible;
    `LspHierarchyNodeDescriptor.update()` shows the containing file name for it instead of an empty row (Consulo deviation).
- `HierarchyNodeDescriptor.getIcon(PsiElement)` (IntelliJ `update()` calls it) - Consulo's `update()` takes the icon from
  `IconDescriptorUpdaters`; `LspHierarchyNodeDescriptor.update()` calls `super.update()` and then `setIcon(getIcon(element))` (the symbol-kind icon).
- `com.intellij.codeInsight.multiverse.EditorContextManager.getPsiFileForEditor` - `PsiDocumentManager.getPsiFile(editor.getDocument())`.
- `com.intellij.ui.tree.ui.DefaultTreeUI` - see above.
- `LineMarkerInfo(..., accessibleNameProvider)` - Consulo's constructor has no accessible-name provider (and needs an `updatePass`, `Pass.LINE_MARKERS`).
- `GutterIconNavigationHandler.navigate(MouseEvent, T)` - Consulo passes a unified `ComponentEvent<?>`; converted with
  `RelativePoint.fromScreen(e).toMouseEvent()` (null when the event has no input details) for `navigateOrShowPopup`.
- `withBackgroundProgress(project, title) { ... }` + `withContext(Dispatchers.EDT)` - `Task.Backgroundable(project, title, true)`; `run` waits
  for the requests (polling `indicator.checkCanceled()`, cancelling the request future on cancellation), `onSuccess` shows the popup.
- `kotlinx.coroutines.sync.Semaphore` / `coroutineScope { async { } }` in `LspInheritanceMarkersCache` - private `AsyncSemaphore`
  (4 permits, a released permit starts the next waiting block on the application executor) and `CompletableFuture.allOf`.
  Coroutine cancellation of the pull -> private `RequestScope`: every server request of one pull is tracked and cancelled when the
  future returned by `sendRequest` is cancelled. In `LspInheritanceMarkersRequestsKt` the dependent futures forward cancellation to the
  request futures (`cancelOnCancellation`), so `$/cancelRequest` still reaches the server.
- `computeSafely` - `CompletableFuture.handle`: a `CancellationException` is rethrown, any other failure is logged (`warn`) and yields `null`.

## 5. Registrations

| Upstream | Consulo |
|---|---|
| `callHierarchyProvider language="" order="last"` | `@ExtensionImpl LspCallHierarchyProvider implements HierarchyProvider<PsiElement>`, `getLanguage()` = `Language.ANY`, `getKind()` = `CALL`, no order |
| `typeHierarchyProvider language="" order="last"` | `@ExtensionImpl LspTypeHierarchyProvider`, same, `getKind()` = `TYPE` |
| `codeInsight.lineMarkerProvider language=""` | `@ExtensionImpl LspInheritanceLineMarkerProvider extends LineMarkerProviderDescriptor implements DumbAware`, `getLanguage()` = `Language.ANY` (the `LineMarkerProvider` cache is built with `LanguageOneToMany.build(true)`, works as is) |
| `registryKey lsp.inheritance.markers.max.symbols` (200), `lsp.inheritance.markers.quiescence.ms` (1000) | read with `Registry.intValue(key, default)` |

**Language-specific providers still win:** upstream `order="last"` is replaced by `LspHierarchyUtilKt.hasLanguageSpecificTarget(dataContext, kind)`,
called first in both `getTarget`s: when any `HierarchyProvider` of the same kind registered for the language (or a base language) of
`PsiElement.KEY` / `PsiFile.KEY` returns a target, the LSP provider returns `null`. This is independent of the extension order.

## 6. Platform change needed (hierarchy does not work without it)

`consulo.language.editor.impl.internal.hierarchy.HierarchyBrowseServiceImpl.CACHE_KEY` is built with `LanguageOneToMany.build(false)`,
so for a caret in an editor (element / file language, e.g. Java or plain text) `Language.ANY` providers are never found: **Call / Type
Hierarchy from the editor does not reach the LSP providers** until it is `build(true)` (like `LineMarkerProvider.KEY`). With `build(true)`
`findBestHierarchyProvider` returns the first provider whose `getTarget` is non-null, which together with `hasLanguageSpecificTarget`
keeps language providers first. "Base on this" from inside an LSP hierarchy already works: the node elements are `LspFakePsiElement`s whose
`getLanguage()` is `Language.ANY`, so the lookup for `ANY` finds only `ANY` providers (the LSP one, via `firstOfKind`), and
`createModel` resolves the client with `findSupportingClient` as upstream.

## 7. Deviations / untested

- Tree root visible, file name shown on it; no limited auto-expand (see 4).
- Content tab title: `HierarchyModel.getContentDisplayName` default uses the target's `getName()`, which is `""` for the editor target
  (upstream has the same empty name; IntelliJ's browser base computes its own title).
- Occurrence navigation (next/previous in the hierarchy tool window) opens `getOpenFileElement().getTextOffset()` = 0 of the file for an
  `LspFakePsiElement` (Consulo's `OccurenceNavigatorSupport` does not use the descriptor's `Navigatable`); double-click / F4 use the
  descriptor's `navigate` (LSP position) as upstream.
- Hierarchy tree children are fetched with `sendRequestSync` in the platform's background builder thread, as upstream.
- `collectSlowLineMarkers` is deprecated in Consulo's `LineMarkerProvider` but still called by `LineMarkersPass`.
- Nothing was run (no maven build). The new hierarchy / lineMarkers classes compile with `javac` against the local m2 Consulo jars and
  the previously built `plugin/target/classes` (only the new `LspHighlightingCacheRegistry.getInheritanceMarkersCache()` was missing there,
  because the old compiled registry was on the classpath).

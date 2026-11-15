# Refactoring / formatting (impl)

Scope: `features/rename/*`, `features/workspaceFileOperations/*`, `features/codeActions/*`, `features/extract/*`, `features/inline/*`,
`features/formatter/*`, the `// TODO workspaceFileOperations stage` in `platformListeners/LspRefactoringEventListener`.
Every `.kt_` of the scope is converted (none left).

New files:
- `features/codeActions/LspRefactoringCodeActionsKt.java` (facade of `LspRefactoringCodeActions.kt`)
- `features/formatter/LspTypedActionHandler.java`, `LspOnTypeFormattingEnterHandler.java`, `LspOnTypeFormattingKt.java` (the
  shared private top-level functions of `LspOnTypeFormatting.kt`), `LspTypedActionHandlerInstaller.java` (Consulo only)
- compat (`plugin/.../consulo/lsp/impl/compat`): `TasksKt` (`runWithModalProgressBlocking`, `await`), `RefactoringEventDataKeys`,
  `FormattingService`, `AsyncFormattingRequest`, `AsyncDocumentFormattingService`, `AsyncDocumentFormattingPostFormatProcessor`
  (Consulo only)

## 1. module-info `requires` / pom (provided)

All come transitively with `consulo-ide-api` / `consulo-language-editor-api`; add the `requires` if they are not there yet:

| JPMS module | maven artifactId | used for |
|---|---|---|
| `consulo.language.code.style.api` | `consulo-language-code-style-api` | `ExternalFormatProcessor`, `PostFormatProcessor`, `FormattingModelBuilder`, `FormattingContext`, `CodeStyle` |
| `consulo.undo.redo.api` | `consulo-undo-redo-api` | `CommandProcessor`, `StartMarkAction`, `FinishMarkAction` |
| `consulo.language.editor.refactoring.api` | (present) | `RenameHandler`, `RefactoringSupportProvider`, `InlineActionHandler`, `ImportOptimizer`, `RefactoringLocalize` |
| `consulo.ui.ex.awt.api` | (present) | `SimpleListCellRenderer` (code action chooser popup) |

No new `opens`.

## 2. Localize keys to add (`com.intellij.platform.lsp.LspLocalize.yaml`)

Used by the compat `AsyncDocumentFormattingService` (IntelliJ `CodeStyleBundle` texts, Consulo has no such keys):

```yaml
async.formatting.service.running:
    text: "Running {0}"
async.formatting.service.timeout:
    text: "{0} does not respond after {1} seconds."
```

Referenced as `LspLocalize.asyncFormattingServiceRunning(name)` / `LspLocalize.asyncFormattingServiceTimeout(name, seconds)`.
`lsp.will.rename.files.skip` stays unused (no custom cancel text in Consulo's modal progress).

## 3. Platform changes needed

- **Extract refactorings never offered.** `RefactoringSupportProvider.forLanguage` is a `LanguageOneToOne` lookup by the file
  language (and base languages) with a dummy default, `BasePlatformRefactoringAction` asks only that one provider, and
  `isAvailableForLanguage` treats the `Language.ANY` default as "none". So providers registered for `Language.ANY` are never asked,
  and four of them would even overwrite each other in the one-to-one map. Needed: a one-to-many lookup
  (IntelliJ `LanguageRefactoringSupport.allForLanguageOrAny`): `BasePlatformRefactoringAction.getHandler` iterates the language
  providers first and then the `Language.ANY` ones, taking the first available one with a non-null handler; `isAvailableForLanguage`
  should also count `ANY` providers. Language-specific providers then still win. The four `LspRefactoringSupportProvider.*`
  are registered for `Language.ANY` and ready.
- **Optimize Imports never uses the LSP optimizer.** `ImportOptimizer.KEY` is built with `LanguageOneToMany.build(false)`, so the
  `Language.ANY` `LspImportOptimizer` is not found by `ImportOptimizer.forFile`. Needed: `build(true)`. `LspImportOptimizer.supports`
  defers to every other optimizer of the language, so the missing `last` order does not matter.
- **Reformat Code is disabled for files without a `FormattingModelBuilder`** (`ReformatCodeAction.updateAsync` checks
  `FormattingModelBuilder.forContext(file) != null`), which is the main LSP formatting case (`LspFormattingSupport` formats
  exclusively by the server only when the IDE cannot format the file by default). Needed: also enable it when
  `ExternalFormatProcessor.useExternalFormatter(file)`.
- **`CodeStyleManagerImpl.reformatText` with an active `ExternalFormatProcessor`** skips the formatter and only runs the
  `PostFormatProcessor`s (IntelliJ had an adapter post-processor calling the external formatter; Consulo has none). Worked around by
  the Consulo-only `AsyncDocumentFormattingPostFormatProcessor` for this plugin's services only. A platform adapter for all
  external formatters would be the proper fix.
- **`workspace/willRenameFiles` is never sent.** Consulo's `RefactoringEventData` has no `RENAMES_KEY` / `TARGET_ELEMENT_KEY`, and
  `RenameProcessor.getBeforeData` / `MoveFilesOrDirectoriesProcessor.getBeforeData` put only the elements (IntelliJ: `addRenames`,
  `addTarget`). Also `BaseRefactoringProcessor` fires `refactoringStarted` inside the write action (IntelliJ: on the EDT outside it),
  so the request (modal progress, then applying edits) cannot run there. Needed: the two keys, filled by the processors, and
  `refactoringStarted` outside the write action. The handler reads the compat `RefactoringEventDataKeys` (same names) for now; switch
  to the platform keys when they exist.
- `ExtensionTypedActionHandler` (`editorTypedHandler`) is in `consulo.codeEditor.internal`; the handler is installed with the
  public `TypedAction.setupHandler` instead (see 4). A public extension point would be cleaner.

## 4. Missing Consulo APIs (`Upstream FQN` - action)

- `com.intellij.platform.ide.progress.runWithModalProgressBlocking` + `kotlinx.coroutines.future.await` - compat `TasksKt`:
  `runWithModalProgressBlocking(Project, LocalizeValue, Supplier)` = `ProgressManager.runProcessWithProgressSynchronously` (background
  thread, cancellable modal progress, exceptions rethrown, user cancel = `ProcessCanceledException`); `await(CompletableFuture)` waits
  with `checkCanceled()` every 10 ms, cancels the future on cancel (-> `$/cancelRequest`), rethrows the completing exception
  (`ResponseErrorException` reaches the callers like the suspend `sendRequest`). The callers (rename, code actions) catch
  `ProcessCanceledException` and return silently (upstream lets the cancellation propagate to the action system).
- `readAndEdtWriteAction` (rename) - the code is already on the UI thread after the modal progress: `ReadAction.compute(create applier)`,
  then `applyWorkspaceEdit()` (which runs its own `WriteAction`). On-type formatting: `Application.invokeLater` + `WriteCommandAction`.
- `TemplateManager.runTemplate` (returns `TemplateState`) / `TemplateResultListener` - `startTemplate(editor, template)` +
  `getTemplateState(editor)` + a `TemplateEditingAdapter` (`templateCancelled` = Canceled, `templateFinished(brokenOff)` =
  BrokenOff / Finished). `Template.addVariable(Variable)` -> `addVariable(name, expr, expr, true, false)`. `Template.isToShortenLongNames`
  - no such option, dropped. `ConstantNode` - compat `consulo.lsp.impl.compat.ConstantNode`.
- `StartMarkAction.start(editor, ...)` / `FinishMarkAction.finish(project, editor, ...)` - the Consulo variants take the `Document`.
- `RefactoringBundle.message("renaming.command.name")` - `RefactoringLocalize.renamingCommandName(name)`.
- `DocumentUtil.executeInBulk(document) {}` - `executeInBulk(document, true, runnable)`.
- `JBPopup.showInBestPositionFor(editor)` (commented out in Consulo) - `EditorPopupHelper.getInstance().showPopupInBestPositionFor(editor, popup)`;
  `textListCellRenderer` - `SimpleListCellRenderer.create("", text)`.
- `RenameHandler.isRenaming` has no default in Consulo - implemented as `isAvailableOnDataContext`. `getActionTitle()` -> `getActionTitleValue()`.
- `ApplicationManagerEx.runProcessWithProgressSynchronously(..., cancelText)` (will-rename) - `ProgressManager.runProcessWithProgressSynchronously`
  without the "Skip" cancel text. `runBlockingCancellable { async {} .awaitAll() }` - all requests are sent at once and awaited one
  after another (a cancel cancels all). `withTimeoutOrNull(1000 ms)` - the request future is cancelled by the app scheduled executor after
  1000 ms (-> `$/cancelRequest`), a cancel / error gives `null`.
- `FakeVirtualFile` (Consulo: `consulo.virtualFileSystem.internal`) - `LspRenameTargetFile` extends the public `StubVirtualFile` with
  `FakeVirtualFile`'s members (parent, name, path, parent file system).
- `rethrowControlFlowException` - rethrow `ControlFlowException` / `ProcessCanceledException`.
- `com.intellij.formatting.service.AsyncDocumentFormattingService` / `AsyncFormattingRequest` / `FormattingService` - minimal compat
  ports. `AsyncDocumentFormattingService implements FormattingService, ExternalFormatProcessor`: `activeForFile` = can format the whole
  file or a fragment; `format(file, range, wsOnly)` builds a `FormattingContext` (`CodeStyle.getSettings(file)`, `REFORMAT`) and calls
  `formatDocument` (the port of `AsyncDocumentFormattingSupportImpl`: per-document pending request + cancel, sync in headless / unit
  test / `FORMAT_DOCUMENT_SYNCHRONOUSLY`, otherwise the task runs in a `Task.Backgroundable` (or the app pool) and the text is applied
  later on the UI thread in an undo-transparent write action; timeout 30 s -> error notification). Not ported: `DocumentMerger` (a
  result for a document changed meanwhile is dropped), `getIOFile()`, timeout actions, structured formatting scope, the `onError`
  overloads with display id / offset. `getNotificationGroupId(): String` -> `getNotificationGroup(): NotificationGroup`, `getName()`
  returns `LocalizeValue`.
- `DocumentImpl(text, false, true)` (LspFormattingTask) - `DocumentImpl` / `DocumentFactory` are not public in Consulo; the edits are
  applied to the text by a private `applyTextEditsToText` with the same sorting and position-to-offset clamping as
  `Lsp4jUtilKt.applyTextEdits`/`getOffsetInDocument`.
- `FormattingContext.getVirtualFile()` - `getContainingFile().getVirtualFile()`; `PsiFile.fileDocument` - `getViewProvider().getDocument()`.
- `LanguageFormatting.INSTANCE.forContext` - `FormattingModelBuilder.forContext`.
- `FormattingService.EP_NAME` / `CoreFormattingService` (`doesOtherFormattingServiceWantToWork`) - iterates the `ExternalFormatProcessor`s
  that are compat `FormattingService`s other than `LspFormattingService`.
- `LanguageImportStatements.INSTANCE.allForLanguageOrAny(language)` - `ImportOptimizer.forLanguage(language)` + `forLanguage(Language.ANY)`.
- `TypedActionHandlerBase(originalHandler)` - Consulo's `TypedActionHandlerBase` has `init(delegate)`; the constructor calls it.
- `CodeStyle.getIndentOptions(project, virtualFile)` - `CodeStyle.getIndentOptions(project, editor.getDocument())`.
- `LspCoroutineScopeService.cs.launch { readAction {} ... }` - `LspCoroutineScopeService.launch(Runnable)` with a blocking
  `ReadAction.compute` (no restart on a write action; the modification stamp is checked again before applying).

## 5. Registration mapping

| upstream xml | Consulo |
|---|---|
| `renameHandler order="last"` `LspRenameHandler` | `@ExtensionImpl RenameHandler, TitledHandler, DumbAware` (order irrelevant: `RenameHandlerRegistry` sorts by title, the default PSI handler is used only when no other is available, as upstream) |
| `inlineActionHandler order="last"` `LspInlineActionHandler` | `@ExtensionImpl InlineActionHandler`; replaces `last` by stepping aside when another `InlineActionHandler` can inline the element in the editor (`isHandledByOtherHandler`) |
| `lang.refactoringSupport language="" order="last"` x4 | `@ExtensionImpl` on `LspRefactoringSupportProvider.IntroduceVariable/ExtractMethod/IntroduceField/IntroduceConstant`, `getLanguage() = Language.ANY`, no order (needs the platform change in 3) |
| `formattingService id="LspFormattingService"` | `@ExtensionImpl(id = "LspFormattingService") LspFormattingService extends compat AsyncDocumentFormattingService` (= `ExternalFormatProcessor`) + `@ExtensionImpl compat AsyncDocumentFormattingPostFormatProcessor implements PostFormatProcessor` |
| `lang.importOptimizer language="" order="last"` | `@ExtensionImpl LspImportOptimizer implements ImportOptimizer`, `Language.ANY`, no order (needs the platform change in 3) |
| `enterHandlerDelegate id="LspOnTypeFormattingEnterHandler" order="last"` | `@ExtensionImpl(id = "LspOnTypeFormattingEnterHandler") extends EnterHandlerDelegateAdapter`, no order; the request starts in `invokeLater` after the Enter action (so delegates after this one cannot change the document after the stamp is taken) |
| `editorTypedHandler id="LspTypedActionHandler" order="last"` | no public EP: `@ExtensionImpl LspTypedActionHandlerInstaller implements PostStartupActivity` installs `LspTypedActionHandler` once per application with `TypedAction.getInstance().setupHandler(new LspTypedActionHandler(getHandler()))` on the UI thread; it wraps every `ExtensionTypedActionHandler` (= upstream `last`) |
| `projectListeners RefactoringEventListener` | (core) `LspRefactoringEventListener.refactoringStarted` now calls `LspWillRenameFilesHandler.renameOrMoveRefactoringStarted` |

## 6. Deviations

- `LspWillRenameFilesHandler.renameOrMoveRefactoringStarted`: the planned renames are computed before the EDT / write-action check
  (upstream after it), so the warning is not logged for every Rename / Move while the platform gives no planned renames and fires
  the event in a write action (see 3).
- Formatting with multiple ranges (`reformatText` with several ranges, e.g. "only VCS changed text"): Consulo calls the
  post-processor once per range, so `formatDocument` is called per range and a request still running for the document rejects the
  next one (upstream gets all ranges in one call). Only the first range is formatted in that case.
- `ExternalFormatProcessor.format` for a fragment when the server has no range formatting returns `null` (no fallback to the IDE
  formatter; IntelliJ would pick `CoreFormattingService`).
- `LspRenameHandler.performRename` / `prepareRename` run the request under a modal progress from the template-finished callback, like
  upstream (the callback may run inside a write action in Consulo).

## 7. Exposed Java signatures

- `LspRefactoringCodeActionsKt.requestAndApplyCodeActions(List<LspClientImpl>, Editor, VirtualFile, List<String> kinds, LocalizeValue progressTitle, LocalizeValue noActionsMessage, LocalizeValue popupTitle[, int offset[, int length]])`, `showErrorHint(Editor, LocalizeValue)`.
- `LspRefactoringSupportProvider.findLspClientsForExtract(PsiFile, List<String>)`, `findPendingLspClientForExtract(PsiFile)`;
  `LspInlineActionHandler.findLspClientsForInline(PsiFile)`, `findPendingLspClientForInline(PsiFile)`.
- `LspImportOptimizer.findClientToOptimizeImports(PsiFile): @Nullable LspClient`.
- `LspWillRenameFilesHandler.renameOrMoveRefactoringStarted(Project, String, RefactoringEventData)` (static, upstream `object`).
- `LspOnTypeFormattingKt.shouldPerformOnTypeFormatting(Editor)`, `performOnTypeFormatting(Editor, VirtualFile, char)`.
- `LspTypedActionHandler(@Nullable TypedActionHandler originalHandler)`.

## 8. Untested

Nothing was run (no Maven build). The new files were compiled with plain `javac -proc:none` against the local m2 jars and the
existing `target/classes`: the only errors were the two missing `LspLocalize` keys of section 2 (and a classpath artifact from
the Kotlin stubs jar shadowing `LocalizeValue`). Not tested in the sandbox: rename (template flow, undo grouping,
prepareRename fallbacks), extract / inline (blocked by the platform for extract), formatting (Reformat Code is disabled for files
without a formatter, see 3; `CodeStyleManager.reformat` paths), on-type formatting (typed handler installation, Enter),
will-rename (blocked by the platform).

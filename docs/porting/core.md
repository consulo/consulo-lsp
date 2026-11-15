# core (impl)

Scope: `plugin/src/main/java/com/intellij/platform/lsp/impl/` top-level files, `cache/*`, `connector/*`, `documentSync/*`,
`fileEvents/*`, `logging/*`, `platformListeners/*`, `util/*`, `features/LspExecuteCommand`, `features/LspFeaturesRefreshing`,
`features/LspPendingClient`, `features/navigation/LspDynamicFiles`. Every `.kt_` of the scope is converted (none left).
New files: `impl/LspNotificationGroupContributor.java`, `impl/connector/LspInitializationException.java` (was a top-level class
of `Lsp4jServerConnector.kt`), `impl/LspDocumentPosition.java` / `LspDocumentRange.java` (top-level data classes of `LspDocument.kt`),
`plugin/.../consulo/lsp/impl/compat/GlobUtilKt.java`.

## 1. module-info `requires` / pom (provided scope)

| JPMS module | maven artifactId |
|---|---|
| `consulo.annotation` | `consulo-annotation` |
| `consulo.application.api` | `consulo-application-api` |
| `consulo.application.content.api` | `consulo-application-content-api` (`FileIndex.isInContent`, via `ProjectFileIndex`) |
| `consulo.code.editor.api` | `consulo-code-editor-api` (`EditorDocumentListener`, `Editor`) |
| `consulo.component.api` | `consulo-component-api` |
| `consulo.container.api` | `consulo-container-api` - **new**, `ContainerPathManager.get().getLogPath()` for the `language-services` log dir |
| `consulo.disposer.api` | `consulo-disposer-api` |
| `consulo.document.api` | `consulo-document-api` |
| `consulo.file.editor.api` | `consulo-file-editor-api` |
| `consulo.language.api` | `consulo-language-api` |
| `consulo.language.editor.api` | `consulo-language-editor-api` |
| `consulo.language.editor.refactoring.api` | `consulo-language-editor-refactoring-api` |
| `consulo.localize.api` | `consulo-localize-api` |
| `consulo.logging.api` | `consulo-logging-api` |
| `consulo.module.content.api` | `consulo-module-content-api` |
| `consulo.navigation.api` | `consulo-navigation-api` (`OpenFileDescriptorFactory`) |
| `consulo.process.api` | `consulo-process-api` (already in pom) |
| `consulo.project.api`, `consulo.project.ui.api` | `consulo-project-api`, `consulo-project-ui-api` (notifications) |
| `consulo.proxy` | `consulo-proxy` - `EventDispatcher` |
| `consulo.ui.api`, `consulo.ui.ex.api` | `consulo-ui-api`, `consulo-ui-ex-api` |
| `consulo.util.collection`, `consulo.util.concurrent`, `consulo.util.dataholder`, `consulo.util.io`, `consulo.util.lang` | `consulo-util-*` |
| `consulo.virtual.file.system.api` | `consulo-virtual-file-system-api` |
| `consulo.web.browser.api` | `consulo-web-browser-api` (`BrowserUtil`, `window/showDocument` with http(s) URIs) |
| `jakarta.inject` | (platform) |
| `org.eclipse.lsp4j`, `org.eclipse.lsp4j.jsonrpc`, `com.google.gson` | already in pom |

All `consulo-*` artifacts except `consulo-container-api` come transitively with `consulo-ide-api`; only the JPMS `requires` are needed.

## 2. `opens`

None. Gson/lsp4j only (de)serialize lsp4j classes here; the lsp4j proxy is created for `descriptor.getLsp4jServerClass()`
(API interface `Lsp4jServer` or a plugin interface) and the endpoint for the API `Lsp4jClient` (or a plugin subclass).

## 3. Missing Consulo APIs

- `com.intellij.ide.trustedProjects.TrustedProjects` / `com.intellij.ide.TrustedFiles` - Consulo has no trusted projects / safe mode: every project and file is treated as trusted (checks removed with comments in `LspClientImpl.start`, `LspClientManagerImpl.startIfNeeded/ensureStarted`, `LspOpenedFilesService.processOpenedFiles`).
- `com.intellij.openapi.util.getPathMatcher` (globUtil.kt) - ported to `plugin/.../consulo/lsp/impl/compat/GlobUtilKt.java`.
- `com.intellij.openapi.extensions.ExtensionPointListener` (`EP_NAME.point.addExtensionPointListener`) - Consulo plugins are not (un)loaded at runtime -> dropped, comment in `LspClientManagerImpl.addExtensionPointListener`.
- `com.intellij.platform.backend.workspace.WorkspaceModel` event log (`ContentRootEntity` changes) - replaced by the project `ModuleRootListener.rootsChanged` topic (subscribed in the `LspClientManagerImpl` constructor, connection disposed with the service) -> `onProjectRootsChanged()`.
- `com.intellij.serviceContainer.AlreadyDisposedException` - no such exception -> `LspClientImpl.start` checks `project.isDisposed()` in the catch block.
- `com.intellij.openapi.application.readAndEdtWriteAction` - `Application.invokeLater` + `WriteAction.compute`/`ReadAction.compute` (`LspClientManagerImpl.ensureStarted`, `LspServerNotificationsHandlerImpl.applyEdit`). `writeCommandAction(project, name)` -> `WriteCommandAction.writeCommandAction(project).withName(name).run(...)`.
- `com.intellij.platform.ide.progress.withBackgroundProgress` + `reportRawProgress` - `Task.Backgroundable(project, LspLocalize.progressTitleProgress(name), cancellable)` polling the token state every 100 ms; the coroutine `Job` is replaced by a small `ProgressJob` (cancels the indicator). A user cancel of a cancellable indicator while the server runs sends `window/workDoneProgress/cancel`; a cancel caused by `End`/a new `Begin`/`cancelAllProgress()` does not (upstream sends it whenever the job is cancelled while Running).
- `NotificationGroupManager` - `NotificationGroup` constant `LspServerNotificationsHandlerImpl.SHOW_MESSAGE_GROUP` (id `LSP window/showMessage`, `BALLOON`, `LspLocalize.notificationGroupLspMessage()`) + `@ExtensionImpl LspNotificationGroupContributor`.
- `com.intellij.notebook.editor.BackedVirtualFile.getOriginFileIfBacked` - no notebook files in Consulo -> the file itself (`LspDocumentSyncManager`).
- `com.intellij.psi.impl.PsiDocumentManagerBase.getTopLevelDocument` (impl class) - unwrap `DocumentWindow.getDelegate()` in a loop (`LspDocumentMapping.unwrapInjection`).
- `com.intellij.openapi.vfs.VirtualFileSetFactory` - plain `HashSet` (`LspDocumentListener`).
- `com.intellij.execution.process.initiateProcessTermination` - `ProcessHandlerStopper.stop` (`Lsp4jServerConnectorUtilKt.stopProcess`).
- `BaseProcessHandler.process.isAlive` (socket connector retry loop) - `!processHandler.isProcessTerminated()`.
- `com.intellij.util.ReflectionUtil.dumpFields(PipedInputStream...)` - java.base internals are not reflectively accessible -> only `available()` is logged.
- `com.intellij.util.io.IOUtil.closeSafe` - plain `socket.close()` with a logged `IOException`.
- `com.intellij.util.io.sanitizeFileName` - `consulo.util.io.PathKt.sanitizeFileName`. `PathManager.getLogPath()` - `ContainerPathManager.get().getLogPath()`.
- `com.intellij.xml.breadcrumbs.BreadcrumbsXmlWrapper.FORCE_RELOAD_BREADCRUMBS` - no such topic (and no `FileBreadcrumbsCollector`) -> `LspFeaturesRefreshing.refreshBreadcrumbs()` is a documented no-op (also called from `LspDocumentSyncManager.open`).
- `com.intellij.codeInsight.codeVision.CodeVisionHost` - no public API -> `LspFeaturesRefreshing.refreshCodeLenses(project)` is a `// TODO codeLens stage` no-op.
- `ProgressIndicatorUtils.awaitWithCheckCanceled(semaphore, ...)` (internal package) - `while (!semaphore.waitFor(10)) ProgressManager.checkCanceled()` (`LspExecuteCommandKt`).
- `FileDocumentManagerListener.unsavedDocumentDropped(document)` - Consulo only has `unsavedDocumentsDropped()` (no document) -> it schedules `scheduleClosingFilesThatAreNotOfInterest()` for every open project instead of `beforeDocumentSaving(document)`.
- `vfs.asyncListenerBackgroundable` - Consulo has only the `AsyncFileListener` extension point -> `@ExtensionImpl LspFileListener implements AsyncFileListener`.
- Kotlin `typealias LspServerImpl = LspClientImpl`, `LspServerManagerImpl = LspClientManagerImpl`, `LspClientRenameCompat = LspServer` - no Java equivalent; files deleted, `LspClientImpl implements LspServer` directly.
- `CoroutineScope` (`LspCoroutineScopeService.cs`, `LspClientManagerImpl.cs`, `LanguageServiceLogger`): see section 6; the `LanguageServiceLogger` flush debounce (`sample(1s).debounce(100ms)`) is a single scheduled flush at most once per second (`AppExecutorUtil.getAppScheduledExecutorService()`).
- `kotlin.time.TimeSource` - `System.nanoTime()`.

## 4. Registration mapping

| Upstream xml | Consulo |
|---|---|
| `projectService serviceInterface=LspClientManager serviceImplementation=LspClientManagerImpl` | `@ServiceImpl @Singleton LspClientManagerImpl implements LspServerManager, Disposable` (`@ServiceAPI` is on the API `LspClientManager`), `@Inject (Project)`. Disposed on project close -> stops every server |
| `applicationService Lsp4jService -> Lsp4jServiceImpl` | `@ServiceImpl @Singleton Lsp4jServiceImpl` |
| `@Service(PROJECT) LspOpenedFilesService`, `@Service(PROJECT) LspCoroutineScopeService` | `@ServiceAPI(PROJECT) @ServiceImpl @Singleton` on the class, `@Inject` |
| `@Service(APP) LanguageServiceLoggerService` | `@ServiceAPI(APPLICATION) @ServiceImpl @Singleton` |
| `postStartupActivity LspStartupActivity` | `@ExtensionImpl LspStartupActivity implements PostStartupActivity, DumbAware` |
| `editorFactoryDocumentListener id=LspDocumentListener` | `@ExtensionImpl(id = "LspDocumentListener") implements EditorDocumentListener` |
| `vfs.asyncListenerBackgroundable id=LspFileListener` | `@ExtensionImpl(id = "LspFileListener") implements AsyncFileListener` |
| `projectListeners FileEditorManagerListener -> LspFileEditorManagerListener` | `@TopicImpl(ComponentScope.PROJECT)` (starts servers on `fileOpened`/`selectionChanged`) |
| `projectListeners RefactoringEventListener -> LspRefactoringEventListener` | `@TopicImpl(ComponentScope.PROJECT)`, `@Inject (Project)` |
| `applicationListeners FileDocumentManagerListener -> LspFileDocumentManagerListener` | `@TopicImpl(ComponentScope.APPLICATION)` |
| `notificationGroup id="LSP window/showMessage"` | `NotificationGroup` constant + `@ExtensionImpl LspNotificationGroupContributor` |
| `extensionPoint com.intellij.platform.lsp.documentAdapter` | `@ExtensionAPI(ComponentScope.APPLICATION) LspDocumentAdapter`, `EP_NAME = ExtensionPointName.create(LspDocumentAdapter.class)`; `DefaultLspDocumentAdapter` is the unregistered fallback |
| `registryKey lsp.server.connect.timeout` (100), `lsp.server.logging.maxLogFiles` (5), `lsp.communication.standard.log.file` (false) | `Registry.intValue/is(key, upstreamDefault)` |

## 5. Files left as `.kt_`, TODOs

No `.kt_` left in the scope. TODOs (later stages):
- serviceView stage: done, see [ui.md](ui.md) (the `// TODO serviceView stage` call sites are wired to `LspServiceViewSupport` / `LspClientConsole` / `ShowLspServerLogAction`).
- `// TODO codeLens stage`: `LspFeaturesRefreshing.refreshCodeLenses`.
- `// TODO workspaceFileOperations stage`: `LspWillRenameFilesHandler.renameOrMoveRefactoringStarted` in `LspRefactoringEventListener`.
- workspaceSymbol stage: `toWorkspaceSymbol` (workspaceSymbol/converters.kt) is ported as a private helper in `LspRequestExecutor` (the converters file stays `.kt_` for that stage).
- Upstream TODOs kept in `LspClientManagerImpl.onProjectRootsChanged` (didChangeWorkspaceFolders).

## 6. Exposed Java signatures (differences from / additions to the rule-predicted ones)

- `LspClientManagerImpl.getClients(Class)` returns `Collection<LspClient>` (Java can't override covariantly with `Collection<LspClientImpl>`); **new** `getClientsImpl(Class): Collection<LspClientImpl>` for internal callers. `getServersForProvider` returns `Collection<LspServer>`.
- `LspClientManagerImpl.ensureStarted(providerClass, descriptor[, @Nullable Long requestStamp])` returns `@Nullable CompletableFuture<Void>` (upstream `Job?`). `onProjectRootsChanged()` returns `CompletableFuture<Void>`. Static `getServerId(Class, LspClientDescriptor)`, `getServerId(LspClientImpl)`, `getInstanceImpl(project)`, `forEachRunningClientInEachProject(Consumer)` / `(@Nullable VirtualFile, Consumer)`, `isAnyServerRunning()`. `LspStarterImpl.getDescriptors()`.
- `LspCoroutineScopeService` (no coroutines): project `Disposable` service with `getInstance(project)`, `isDisposed()`, `launch(Runnable): Future<?>` (= `cs.launch {}` on the app pool, skipped after dispose) and `future(Supplier<T>): CompletableFuture<T>` (= `cs.future {}`). Other parts should use `LspCoroutineScopeService.getInstance(project).launch(...)` for `cs.launch`.
- `LspClientImpl`: getters per rules (`getDocumentMapping()`, `getRequestExecutor()`, `getGlobMatcher()`, `getDynamicCapabilities()`, `getServerNotificationsHandler()`, `getDocumentSyncManager()`, `getWatchedFiles()`, `getDynamicFiles()`, `getHighlightingCacheRegistry()`, `getErrorOutput()`, `getLsp4jServer()`, `getClientCapabilities()`, `getServerCapabilities()`), `forEachOpenedFile(Consumer<VirtualFile>)`, `ensureServerStopped(boolean, Runnable)`, `logWarn(String)` + `logWarn(String, @Nullable Throwable)`, `public static final int NOT_CANCELLABLE_REQUEST_TIMEOUT_MS`. Constructor is public.
- `LspRequestExecutorBase.sendRequest(Function)` returns `CompletableFuture<@Nullable R>` (cancel -> `$/cancelRequest`); `sendRequestSync(Function)` / `(int, Function)`; `awaitSync(future[, timeoutMs])`; `sendRequestAsyncButWaitForResponseWithCheckCanceled(Function, Consumer<@Nullable R>)`.
- `LspDocumentMapping.forEachDocumentInFile(file, Function<LspDocument, CompletableFuture<T>>)` and `(file, BiFunction<LspDocument, Range, CompletableFuture<T>>)` return `CompletableFuture<List<T>>` (blocks run one after another, a cancel of the result cancels the in-flight block). `withDocumentAtOffset/withDocumentAtFileOffset(..., BiFunction<LspDocument, Position, @Nullable T>)`. Statics `aggregatePerDocumentResults(List<? extends @Nullable List<T>>)`, `aggregateToPullResult(List<? extends @Nullable List<Pair<Range, T>>>)` (`consulo.util.lang.Pair`), `getDocumentMapping(LspClient)`.
- `LspDocument.mapToHost(T, BiFunction<LspDocument, T, T>)` (Kotlin receiver = first argument), `prepareDocument(Document, Function<Document, @Nullable Document>)`. `LspDocumentAdapter` suspend functions return `CompletableFuture<List<...>>`.
- `LspDynamicCapabilities` capability constants are static getters returning `consulo.util.lang.Pair<String, Class<XxxOptions>>` (`getDiagnostic()`, `getCodeAction()`, `getDidChangeWatchedFiles()`, ...); `hasCapability(String)` / `hasCapability(Pair<String, ? extends Class<?>>)`.
- `LspServerCapabilitiesKt` (extension functions with the client as first parameter), `LspResponseMappingKt`, `LspInitializationOptionsKt`, `Lsp4jConvertersKt`, `Lsp4jServerConnectorUtilKt`, `LspExecuteCommandKt`, `LspPendingClientKt`: facade classes per rules. `supportsCodeActions(client, Predicate<List<String>>)`.
- `LspPerFileCache.Matcher<K, V>` (`matches(storedKey, storedValue, queriedKey)`) is the 3-arity function type of `LspPerFileCache`/`LspSingleSlotCache`; constructors `(project)`, `(project, invalidateOnlyOnDocumentChange)`, `(project, invalidateOnlyOnDocumentChange, Matcher)`. Kotlin `Unit` key -> `Object` key with `LspPerFileCache.FILE_LEVEL_KEY`, extension `getOrCompute(file, compute)` -> static `LspPerFileCache.getOrCompute(LspPerFileCache<Object, V>, VirtualFile, Supplier)`.
- `LspServerNotificationsHandlerImpl`: `getNextApplyEditHandler()/setNextApplyEditHandler(@Nullable Consumer<WorkspaceEdit>)`, `SHOW_MESSAGE_NOTIFICATION_GROUP` (String) + `SHOW_MESSAGE_GROUP` (NotificationGroup).
- `LspDynamicFiles.CONTENT_BY_SERVER_ID` is a `public static final Key<String>` field (like the API's `LspClientDescriptor.DYNAMIC_FILE_URI`); `getDynamicFileUri(file)` static.
- `LspRefactoringEventListener.RENAME_REFACTORING_ID` / `MOVE_REFACTORING_ID` (top-level consts of its file) are static constants of the class.
- `Lsp4jServerConnector`: `connect(BiConsumer<InitializeParams, InitializeResult>) throws Exception`, abstract `getIdeToServerStream()/getServerToIdeStream()`; connectors' constructors throw `ExecutionException` (process start).
- `LanguageServiceLoggerService.millisToDate(long)` (top-level internal fun of its file) is a static of the service.

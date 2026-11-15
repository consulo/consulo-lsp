# api module conversion notes

Scope: `api/src/main/java/com/intellij/platform/lsp/{api,api/customization,util}`. Built with
`mvn -q -B package -Dmaven.test.skip=true -Dmaven.build.cache.enabled=false -f api/pom.xml`: compiles clean (NullAway on).

## 1. Missing Consulo APIs

- `com.intellij.platform.lsp.api.LspBundle` - not ported (per CONSULO.md); no API file uses it any more (only lsWidget did).
- `com.intellij.execution.process.OSProcessHandler` - Consulo's one is in `consulo.process.internal`, and `ProcessHandlerBuilder` can't override `readerOptions()` (LSP needs `splitToLines() = false`, or `\r` of `\r\n` is lost and `Content-Length` breaks). Ported a minimal `consulo.lsp.compat.OSProcessHandler extends consulo.process.BaseProcessHandler` (BaseOSProcessHandler + LocalProcessHandler + OSProcessHandler merged; overridable `readerOptions()`, `getProcess()`, `getCommandLine()`, `getProcessInput()`; destroys the process tree via `ProcessHandle.descendants()`).
- `com.intellij.openapi.project.BaseProjectDirectories.getBaseDirectories` - ported as `consulo.lsp.compat.BaseProjectDirectories.getBaseDirectories(Project): Set<VirtualFile>` = `ProjectRootManager.getContentRoots()` minus roots nested in other roots (computed per call, no service/topic).
- `com.intellij.platform.eel.*` (EelDescriptor, spawnProcess, asEelPath, ...) - treated as local: `startServerProcessViaEel` dropped, `getFilePath` = `file.getPath()`, `findLocalFileByPath` = `LocalFileSystem.findFileByPath`; comments left in place.
- `com.intellij.util.PlatformUtils.isJetBrainsClient` (`cwm://` lookup) - dropped together with the EEL branch.
- `com.intellij.openapi.application.ApplicationNamesInfo` / `ApplicationInfo` (ClientInfo) - `Application.get().getName().get()` and `Application.get().getBuildNumber().asString()`.
- `com.intellij.openapi.vfs.VfsUtil.toUri(String)` - `consulo.virtualFileSystem.util.VirtualFileUtil.toUri(String)` (older but equivalent Windows-drive handling).
- `com.intellij.openapi.vfs.VfsUtil.createDirectoryIfMissing` - `VirtualFileUtil.createDirectoryIfMissing` (IOException wrapped into UncheckedIOException, upstream lets it propagate).
- `com.intellij.openapi.vfs.StandardFileSystems.local()` - `LocalFileSystem.getInstance()`.
- `com.intellij.openapi.components.serviceOrNull` (LspIntegrationSettings) - Consulo services can't be optional: `LspIntegrationSettings` is an `@ExtensionAPI(ComponentScope.PROJECT)`; `getInstanceOrNull(project)` returns the first extension or null.
- `com.intellij.codeInsight.intention.preview.IntentionPreviewInfo` / `IntentionAction.generatePreview` - no Consulo API; `LspIntentionAction.generatePreview`/`invokeForPreview` not ported (`// TODO intention preview:`).
- `com.intellij.codeInsight.intention.IntentionAction.getFamilyName` - not in Consulo's interface; kept as a plain `public final String getFamilyName()`.
- `com.intellij.openapi.fileEditor.FileEditorOpenRequest` / `requestOpenTextEditor` - `OpenFileDescriptorFactory.getInstance(project).newBuilder(file).offset(..)/line(..).column(..).build()` + `FileEditorManager.openTextEditor(descriptor, focus)`.
- `com.intellij.openapi.ui.popup.JBPopup.showInBestPositionFor(Editor)` - `editor.showPopupInBestPositionFor(popup)`.
- `com.intellij.openapi.application.runReadActionBlocking` - `ReadAction.compute`.
- `com.intellij.openapi.diagnostic.fileLogger()/thisLogger()` - `Logger.getInstance(<facade or class>.class)`.
- `com.intellij.icons.AllIcons.*` - `consulo.platform.base.icon.PlatformIconGroup` (`consulo.ui.image.Image` instead of `Icon`). No exact match for `Nodes.Word` -> `filetypesText()`, `Actions.Colors` -> `gutterColors()`, `Json.Object` -> `nodesStruct()`.
- `com.intellij.platform.lang.lsWidget.LanguageServiceWidgetItem` (+ the whole lsWidget) - later stage, see 2.

Plain mappings (no decision needed): `consulo.process.{ExecutionException,BaseProcessHandler,cmd.GeneralCommandLine,io.BaseOutputReader,io.BaseDataReader}`, `consulo.colorScheme.{TextAttributes,TextAttributesKey}`, `consulo.codeEditor.{DefaultLanguageHighlighterColors,HighlighterColors,Editor}`, `consulo.language.editor.annotation.{AnnotationHolder,AnnotationBuilder,HighlightSeverity}`, `consulo.language.editor.inspection.ProblemHighlightType`, `consulo.language.editor.intention.IntentionAction`, `consulo.language.editor.FileModificationService`, `consulo.language.editor.completion.{CompletionParameters,lookup.*}`, `consulo.navigation.{NavigationItem,ItemPresentation}`, `consulo.document.{Document,DocumentWindow,FileDocumentManager,util.TextRange}`, `consulo.module.content.ProjectFileIndex`, `consulo.util.io.{URLUtil,OSAgnosticPathUtil,PathUtil}`, `consulo.util.lang.StringUtil` (also for `StringUtilRt.convertLineSeparators`), `consulo.virtualFileSystem.fileType.FileNameMatcherFactory`, `consulo.ui.ex.popup.JBPopupFactory`, `consulo.ui.ex.awt.ColoredListCellRenderer`, `consulo.ui.ex.{SimpleTextAttributes,RelativePoint}`, `consulo.disposer.Disposable`.

## 2. Files intentionally left as `.kt_`

- `api/lsWidget/LspClientWidgetItem.kt_`, `LspServerWidgetItem.kt_`, `LspWidgetInternalService.kt_` - Language Services status-bar widget is a later stage; they depend on `com.intellij.platform.lang.lsWidget.*` (no Consulo equivalent). References removed with `// TODO lsWidget stage:` from `LspIntegrationProvider` (`createWidgetItems`, `createWidgetItem`) and `LspServerSupportProvider` (`createLspWidgetItems`, `createLspServerWidgetItem` and their overrides). The impl's `LspWidgetItemsProvider` / `LspWidgetInternalServiceImpl` have nothing to call yet.

## 3. Deviations from the CONSULO.md rules / non-obvious Java signatures

- **`Lsp4jServer`** (Kotlin `typealias` of `LanguageServer`) is `public interface Lsp4jServer extends org.eclipse.lsp4j.services.LanguageServer {}`. All API signatures use `Lsp4jServer` (sender lambdas, `Lsp4jServerWrapper`, `getLsp4jServerClass(): Class<? extends Lsp4jServer>`, default `Lsp4jServer.class`). The impl must create the lsp4j remote proxy with `descriptor.getLsp4jServerClass()` so the proxy implements `Lsp4jServer`.
- **`LspClient.sendRequest`** returns `CompletableFuture<@Nullable Lsp4jResponse>` (NullAway checks generic nullability: an override must use exactly this type, and complete with `null` where upstream returns `null`).
- **`LspClient.sendRequestSync`**: the Kotlin default is the *leading* `timeoutMs` parameter, so the overloads are `sendRequestSync(Function)` (default method -> `DEFAULT_REQUEST_TIMEOUT_MS`) and abstract `sendRequestSync(int timeoutMs, Function)`.
- **`LspClient.DEFAULT_REQUEST_TIMEOUT_MS`** is an interface constant; `LspServer.DEFAULT_REQUEST_TIMEOUT_MS` hides it (deprecated).
- **`LspServer.getDescriptor(): LspServerDescriptor`** shim not ported (would clash with `LspClient.getDescriptor(): LspClientDescriptor`); callers cast.
- **`LspClientDescriptor.getLanguageId(file)` companion** -> `public static String getDefaultLanguageId(VirtualFile)` (name clash with the instance method). The instance `getLanguageId(VirtualFile)` delegates to it. `LspServerDescriptor`'s deprecated companion `getLanguageId` dropped for the same reason.
- **`LspClientDescriptor.DYNAMIC_FILE_URI`** is a `public static final Key<String>` field (not `getDYNAMIC_FILE_URI()`); `LOG` is a `public static final Logger` field (both classes).
- **`LspClientDescriptor.startServerProcess()`** returns `consulo.process.BaseProcessHandler` (non-generic) and creates `consulo.lsp.compat.OSProcessHandler`; `LspServerDescriptor.startServerProcess()` overrides it covariantly to `consulo.lsp.compat.OSProcessHandler`. `BaseProcessHandler` has no `getProcess()`; use `isProcessTerminated()` or cast to the compat `OSProcessHandler`.
- **`LspClientDescriptor.getRoots()`** returns `VirtualFile[]` (vararg val).
- **Reified inline extensions** (`LspClientManager.getClients<P>()`, `startClientsIfNeeded<P>()`, ..., `LspServerManager.getServersForProvider<P>()`, ...) not ported.
- **`LspIntegrationProvider.getAllExtensions()`** returns `List<LspIntegrationProvider>` (upstream `Sequence`); `hasAnyExtensions()` is a static interface method.
- **`LspClientManager.addListener`**: `addListener(listener, parentDisposable)` default + abstract `addListener(listener, parentDisposable, boolean sendEventsForExistingClients)`. Same pattern for the deprecated `LspServerManager.addLspServerManagerListener`.
- **Sealed customizers**: `public abstract sealed class LspXxxCustomizer permits LspXxxSupport, LspXxxDisabled` (package-private ctor); `LspXxxSupport` is `public non-sealed class`; `LspXxxDisabled` is `public final class` with `INSTANCE`. Subclasses are top-level classes (as upstream), not nested.
- **Customizer `open val` -> getters keep Kotlin names**: `getQuickFixesSupport()`, `getIntentionActionsSupport()`, `getInlineRefactoringSupport()`, `getExtractRefactoringSupport()`, `getStructureViewSupport()`, `getBreadcrumbsSupport()`, `getTokenTypes()`, `getTokenModifiers()`, `getSupportedKinds()`, `LspCommunicationChannel.Socket.getStartProcess()`, `LspClientDescriptor.getOfferServerLogInNotifications()`, `LspServerEnvironmentData.getPassParentEnvironment()`.
- **`DeprecatedLspCustomization`** is public; the internal top-level `defaultLspCustomization` is `DeprecatedLspCustomization.getDefaultLspCustomization()`.
- **`LspClientCapabilities.kt`** -> `LspClientCapabilitiesKt.createClientCapabilities(LspCustomization)` (public).
- **`LspIntentionAction.getText()`** returns `LocalizeValue` (Consulo's `IntentionAction`); `LspDiagnosticsSupport.getMessage/getTooltip` stay `String` (raw server text, passed to the `String` overloads of `AnnotationHolder`).
- **`LspCompletionSupport.expensiveRenderLookupElement`** (suspend) -> `CompletableFuture<Void>` (completed synchronously after `renderLookupElement`).
- **`LspCompletionSupport.getIcon` / `LspSymbolKindCustomizer.getIcon`** return `consulo.ui.image.Image`.
- **`LspServerNotificationsHandler.configuration`** returns `CompletableFuture<List<Object>>` (upstream `List<Any?>`; NullAway rejects `List<@Nullable Object>` against lsp4j's `LanguageClient.configuration`). Elements may still be `null` at runtime.
- **`Lsp4jService`** is `@ServiceAPI(ComponentScope.APPLICATION) abstract class`; `getInstance()` = `Application.get().getInstance(Lsp4jService.class)`. Impl needs `@ServiceImpl @Singleton`.
- **`LspClientManager`** is `@ServiceAPI(ComponentScope.PROJECT)`; `getInstance(project)` = `project.getInstance(LspClientManager.class)`. Impl must implement `LspServerManager` (deprecated `LspServerManager.getInstance` casts), and its client objects must implement `LspServer`.
- **`LspIntegrationProvider`**, **`LspServerSupportProvider`**, **`LspIntegrationSettingsProvider`** are `@ExtensionAPI(ComponentScope.APPLICATION)` with `EP_NAME = ExtensionPointName.create(X.class)`; `LspIntegrationSettings` is `@ExtensionAPI(ComponentScope.PROJECT)` (see 1).
- `util` facades: `Lsp4jUtilKt` (getLsp4jPosition, getLsp4jRange, getOffsetInDocument -> `@Nullable Integer`, getRangeInDocument, applySimpleTextEdits, applyTextEdits, applyTextEdit, `getMessageIfStringOrEmpty(Diagnostic)`), `LspNavigationUtilsKt` (navigateOrShowPopup, navigateToLocation, showLspNavigationPopup; `title` is `String`).
- Threading: `@RequiresReadLock` -> `@RequiredReadAction`, `@RequiresEdt` -> `@RequiredUIAccess`, `@RequiresWriteLock` -> `@RequiredWriteAction`; "background thread" contracts kept as Javadoc.

## 4. Key API in Java

```java
public interface LspClient {
    int DEFAULT_REQUEST_TIMEOUT_MS = 10_000;
    Class<? extends LspIntegrationProvider> getProviderClass();
    Project getProject();
    LspClientDescriptor getDescriptor();
    LspServerState getState();
    @Nullable InitializeResult getInitializeResult();
    void sendNotification(Consumer<Lsp4jServer> lsp4jSender);
    void invalidateServerResults();
    <Lsp4jResponse> CompletableFuture<@Nullable Lsp4jResponse> sendRequest(Function<Lsp4jServer, CompletableFuture<Lsp4jResponse>> lsp4jSender);
    default <Lsp4jResponse> @Nullable Lsp4jResponse sendRequestSync(Function<Lsp4jServer, CompletableFuture<Lsp4jResponse>> lsp4jSender);
    <Lsp4jResponse> @Nullable Lsp4jResponse sendRequestSync(int timeoutMs, Function<Lsp4jServer, CompletableFuture<Lsp4jResponse>> lsp4jSender);
    TextDocumentIdentifier getDocumentIdentifier(VirtualFile file);
    int getDocumentVersion(Document document);
    int nextDocumentVersion(Document document);
}
@Deprecated public interface LspServer extends LspClient { int DEFAULT_REQUEST_TIMEOUT_MS; }
```

`LspClientDescriptor` (abstract class; ctor `protected LspClientDescriptor(Project, String presentableName, VirtualFile... roots)`):
- final getters: `getProject()`, `getPresentableName()`, `getRoots(): VirtualFile[]`; static `LOG`, `DYNAMIC_FILE_URI`, `getDefaultLanguageId(VirtualFile)`
- abstract: `@RequiredReadAction boolean isSupportedFile(VirtualFile)`
- overridable: `BaseProcessHandler startServerProcess() throws ExecutionException`, `GeneralCommandLine createCommandLine() throws ExecutionException`,
  `LspCommunicationChannel getLspCommunicationChannel()` (default `LspCommunicationChannel.StdIO.INSTANCE`), `String getFileUri(VirtualFile)`,
  `String getFilePath(VirtualFile)`, `@Nullable VirtualFile findFileByUri(String)`, `protected @Nullable VirtualFile findLocalFileByPath(String)`,
  `String getLanguageId(VirtualFile)`, `InitializeParams createInitializeParams()`, `ClientCapabilities getClientCapabilities()`,
  `@Nullable Object createInitializationOptions()`, `Lsp4jClient createLsp4jClient(LspServerNotificationsHandler)`,
  `Class<? extends Lsp4jServer> getLsp4jServerClass()`, `@Nullable LspServerListener getLspServerListener()`,
  `boolean getOfferServerLogInNotifications()`, `LspCustomization getLspCustomization()` (field created once per descriptor),
  `@Nullable Object getWorkspaceConfiguration(ConfigurationItem)`, `toString()`.

`LspServerDescriptor extends LspClientDescriptor` (deprecated): `OSProcessHandler startServerProcess()`, `getLspCustomization()` = `DeprecatedLspCustomization(this)`,
deprecated `boolean getLspGoToDefinitionSupport()/getLspGoToTypeDefinitionSupport()/getLspHoverSupport()` and `@Nullable LspXxxSupport getLspXxxSupport()` for
Completion, SemanticTokens, Diagnostics, CodeActions, Commands, Formatting, FindReferences, DocumentColor, DocumentLink.

`LspCustomization` getters (each returns the field created in the constructor; override to customize):
`getGoToDefinitionCustomizer`, `getGoToTypeDefinitionCustomizer`, `getGoToImplementationCustomizer`, `getHoverCustomizer`, `getCompletionCustomizer`,
`getSemanticTokensCustomizer`, `getDiagnosticsCustomizer`, `getCodeActionsCustomizer`, `getCommandsCustomizer`, `getFormattingCustomizer`,
`getOnTypeFormattingCustomizer` (default `LspOnTypeFormattingDisabled.INSTANCE`), `getFindReferencesCustomizer`, `getOptimizeImportsCustomizer`,
`getDocumentColorCustomizer`, `getDocumentLinkCustomizer`, `getFoldingRangeCustomizer`, `getInlayHintCustomizer`, `getDocumentHighlightsCustomizer`,
`getSignatureHelpCustomizer`, `getDocumentSymbolCustomizer`, `getWorkspaceSymbolCustomizer`, `getCallHierarchyCustomizer`, `getTypeHierarchyCustomizer`,
`getSymbolKindCustomizer` (`LspSymbolKindCustomizer`, plain open class), `getSelectionRangeCustomizer`, `getCodeLensCustomizer`,
`getInheritanceMarkersCustomizer` (default `LspInheritanceMarkersDisabled.INSTANCE`), `getRenameCustomizer`.
Typical use: `if (customization.getHoverCustomizer() instanceof LspHoverSupport) ...`, `customization.getCompletionCustomizer() instanceof LspCompletionSupport s ? ...`.

`LspCommunicationChannel` (`abstract sealed class`, private ctor):
- `LspCommunicationChannel.StdIO.INSTANCE` (final, singleton, `toString()` = `StdIO`)
- `new LspCommunicationChannel.Socket(int port)` / `Socket(int port, boolean startProcess)`; `getPort()`, `getStartProcess()`; equals/hashCode/toString.

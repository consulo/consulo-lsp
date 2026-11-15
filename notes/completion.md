# completion / documentation / parameterInfo

Scope: `plugin/src/main/java/com/intellij/platform/lsp/impl/features/{completion,documentation,parameterInfo}`.
All `.kt_` files of the scope are converted (none left).

## 1. module-info `requires` / pom (provided scope)

| JPMS module | maven artifactId |
|---|---|
| `consulo.application.api` | `consulo-application-api` (via `consulo-ide-api`) |
| `consulo.code.editor.api` | `consulo-code-editor-api` (via `consulo-ide-api`) |
| `consulo.document.api` | `consulo-document-api` (via `consulo-ide-api`) |
| `consulo.language.api` | `consulo-language-api` (via `consulo-ide-api`) |
| `consulo.language.editor.api` | `consulo-language-editor-api` (via `consulo-ide-api`) |
| `consulo.language.impl` | `consulo-language-impl` - **new**, for `consulo.language.impl.psi.FakePsiElement` |
| `consulo.logging.api`, `consulo.project.api`, `consulo.util.lang`, `consulo.ui.ex.api`, `consulo.virtual.file.system.api` | (via `consulo-ide-api`) |
| `com.google.gson` | `gson` (already there) |
| `org.commonmark`, `org.commonmark.ext.gfm.tables`, `org.commonmark.ext.gfm.strikethrough` | `org.commonmark:commonmark`, `org.commonmark:commonmark-ext-gfm-tables`, `org.commonmark:commonmark-ext-gfm-strikethrough` (0.24.0, version managed by Consulo's `arch/managment/pom.xml`) - **new** |

commonmark is shipped by the Consulo platform (`distribution/pom.xml`, used by `desktop-awt-ui-impl` `MessageListMarkdown`),
all three jars are real JPMS modules. Use `provided` scope and `requires org.commonmark; requires org.commonmark.ext.gfm.tables;
requires org.commonmark.ext.gfm.strikethrough;`. If the platform layer turns out not to be visible to the plugin
(e.g. a non-desktop platform), bundle the three jars into the plugin (compile scope) instead.

## 2. `opens`

None. No gson/lsp4j reflection on my classes (only lsp4j's own `CompletionItem` etc. are (de)serialized).

## 3. Missing Consulo APIs

- `com.intellij.platform.backend.documentation.DocumentationTargetProvider` / `DocumentationTarget` / `DocumentationResult` / `TargetPresentation` / `com.intellij.model.Pointer` / `Symbol` - no such API in Consulo. `LspDocumentationTargetProvider` kept (method `documentationTargets(PsiFile, int)` kept) but is a `DocumentationProviderEx implements UnrestrictedDocumentationProvider` (all languages). How Consulo finds the quick-doc target (`DocumentationTargetFinder.findTargetElementUnsafe`, used by Ctrl+Q, the doc tool window and the mouse-hover doc via `DocumentationManager.findTargetElement`): first `DocumentationProviderEx.getCustomDocumentationElement(editor, file, contextElement, offset)` of the composite provider of the file (language providers first, then all `UnrestrictedDocumentationProvider`s), then `TargetElementUtil`. So `getCustomDocumentationElement` returns the first hover target, `LspDocumentationTargetProvider.LspDocumentationTarget extends FakePsiElement` (non-physical -> Consulo keeps a hard pointer; parent/containing file = host PsiFile; `getName()` = upstream presentation), and `generateDoc` renders it. Lookup items: `getDocumentationElementForLookupItem` wraps `LspCompletionObject` into `LspDocumentationTargetProvider.LspCompletionDocumentationTarget extends FakePsiElement`. `DocumentationResult.Documentation` -> html `String`.
  Behaviour differences: the LSP hover wins over the PSI-based target search when a language doc provider does not return a custom element first; Consulo's mouse-hover doc skips `PsiPlainText`/whitespace leaves (platform: `EditorMouseHoverPopupManagerImpl`), so hover docs do not appear in plain-text PSI files (Ctrl+Q works).
- `com.intellij.lang.documentation.QuickDocHighlightingHelper` - ported (`guessLanguage`, `getStyledSignatureFragment`, + `getStyledCodeFragment`) to `plugin/.../consulo/lsp/impl/compat/QuickDocHighlightingHelper.java` on Consulo's `CodeBlockHighlighter` service.
- `com.intellij.markdown.utils.doc.DocMarkdownToHtmlConverter` (platform/markdown-utils, JetBrains markdown lib) - ported `convert(project, markdown[, defaultLanguage])` to `plugin/.../consulo/lsp/impl/compat/DocMarkdownToHtmlConverter.java` on commonmark-java 0.24 + GFM tables/strikethrough; fenced/indented code blocks highlighted by `QuickDocHighlightingHelper` (fence info string language, else `defaultLanguage`, else escaped). Chosen over the Dart port's approach (consulo-dart `MarkdownToHtmlConverterKt` uses the `consulo.markdown` plugin's JetBrains-markdown engine = hard dependency on another plugin); commonmark is already a platform library. Raw HTML in markdown is passed through (as IntelliJ). Thematic break is rendered `<hr />`, so the upstream `removePrefix("<hr />")` works.
- `com.intellij.codeInsight.template.impl.ConstantNode.withLookupStrings` - Consulo's `ConstantNode` has no lookup items -> ported to `plugin/.../consulo/lsp/impl/compat/ConstantNode.java` (`withLookupStrings`, `withLookupItems`).
- `com.intellij.codeInsight.template.Template.addVariable(Variable)` - missing -> `addVariable(name, expression, defaultValueExpression, alwaysStopAt, skipOnStart)` with the values of the created `Variable`. `TemplateManager.runTemplate` -> `startTemplate`.
- `com.intellij.codeInsight.completion.BaseCompletionService.FORBID_WORD_COMPLETION` / `CompletionProcessEx` - missing (CompletionProcessEx is ide-impl) -> dropped with `// TODO Consulo` comment. Consulo's `WordCompletionContributor` (id `wordCompletion`, order last) only runs for explicit (invocationCount>0) completion or dumb mode; it may add word variants next to LSP items there. Platform improvement: a key like FORBID_WORD_COMPLETION read by `WordCompletionContributor`.
- `com.intellij.codeInsight.completion.FrontendCompletionContributor` - dropped (no remote dev split).
- `com.intellij.codeInsight.lookup.LookupElementDecorator.getDecoratorInsertHandler()` - missing; `LspLookupElementDecorator.handleInsert()` calls `LspCompletionItemInsertHandler.INSTANCE` (method `getDecoratorInsertHandler()` kept, non-override). Same semantics as IntelliJ's `handleInsert` when the decorator insert handler is set.
- `com.intellij.codeInsight.lookup.SuspendingLookupElementRenderer` - missing; `LspExpensiveRenderer` is a plain `LookupElementRenderer`. Consulo runs expensive renderers on a background thread in a cancellable non-blocking read action (`LookupBase`), so `completionItem/resolve` and `LspCompletionSupport.expensiveRenderLookupElement` (a `CompletableFuture`) are awaited with `ProgressManager.checkCanceled()` polling; a PCE cancels the future / the request (`sendRequestSync` cancels on PCE).
- `kotlinx.coroutines.sync.Semaphore` - `java.util.concurrent.Semaphore(2)`, permit acquired by `tryAcquire(10ms)` + `checkCanceled()` loop.
- `com.intellij.codeInsight.CodeInsightBundle "documentation.tool.window.title"` - `consulo.ui.ex.localize.UILocalize.toolWindowNameDocumentation()`.
- `ParameterInfoUIContext.setupUIComponentPresentation(...)` - deprecated in Consulo (it splits the label at commas); `LspParameterInfoHandler.updateUI` uses `context.signature()` builder: `text(before)`, `parameter(highlighted, HIGHLIGHT)`, `text(after)`, `apply()`, label kept as is.
- `ParameterInfoHandler` defaults `couldShowInLookup()`/`getParametersForLookup()` - abstract in Consulo, implemented as IntelliJ defaults (`false`/`null`).

## 4. Registrations

| upstream xml | Consulo |
|---|---|
| `typedHandler id="LspAutoPopupTypedHandler" order="last"` | `@ExtensionImpl(id = "LspAutoPopupTypedHandler", order = "last")` on `LspAutoPopupTypedHandler extends TypedHandlerDelegate` |
| `typedHandler id="LspParameterInfoTypedHandler" order="last, before LspAutoPopupTypedHandler"` | `@ExtensionImpl(id = "LspParameterInfoTypedHandler", order = "last, before LspAutoPopupTypedHandler")` |
| `completion.contributor id="LspCompletionContributor" language="any" order="last, before wordCompletion, before TextMateCompletionContributor"` | `@ExtensionImpl(id = "LspCompletionContributor", order = "last, before wordCompletion")`, `getLanguage()` = `Language.ANY` (CompletionContributor cache is built with ANY). No TextMate contributor in Consulo. |
| `weigher id="LspCompletionWeigher" key="completion" order="after priority, before prefix"` | `@ExtensionImpl(id = "LspCompletionWeigher", order = "after priority, before prefix")` on `LspCompletionWeigher extends CompletionWeigher` (key = `CompletionService.RELEVANCE_KEY`) |
| `codeInsight.parameterInfo id="LspParameterInfoHandler" language=""` | `@ExtensionImpl(id = "LspParameterInfoHandler")`, `getLanguage()` = `Language.ANY` |
| `platform.backend.documentation.targetProvider id="LspDocumentationTargetProvider"` | `@ExtensionImpl(id = "LspDocumentationTargetProvider")` on `LspDocumentationTargetProvider extends DocumentationProviderEx implements UnrestrictedDocumentationProvider` |

**Platform issue (parameter info):** `ParameterInfoHandler.KEY` is built with `LanguageOneToMany.build(false)`, i.e. a handler for
`Language.ANY` is NOT returned by `ParameterInfoHandler.forLanguage(language)`, and `ShowParameterInfoAction` / `ParameterInfoController`
only look up the language at the caret and the base language. So `LspParameterInfoHandler` is not found until the platform
changes `ParameterInfoHandler.KEY` to `LanguageOneToMany.build(true)` (one line in
`consulo/modules/base/language-editor-api/.../parameterInfo/ParameterInfoHandler.java`). Not changed (outside scope).
`CompletionContributor` already uses `build(true)`; documentation uses `UnrestrictedDocumentationProvider` (no language).

## 5. Files left as .kt_ / TODOs

None left. TODOs: `LspCompletionContributor` FORBID_WORD_COMPLETION (see 3); upstream `TODO revisit all the rest properties` kept.

## 6. Java signatures that differ from what the rules predict

- `HoverResultCache(Project)`, `getOrCompute(VirtualFile, Integer, Supplier<@Nullable TextRangeAndMarkupContent>)` - as predicted.
- `TextRangeAndMarkupContent.fromHover(Hover, @Nullable Document, int)` static (companion), `getTextRange()`, `getMarkupContent()`.
- `LspDocumentationData.createLspDocumentationData(MarkupContent)` - static on `LspDocumentationData` (the file declares that class).
- `LspDocumentationData.toQuickDocHtml(Project)` returns **`String`** html (upstream `DocumentationResult.Documentation`).
- `LspCompletionObject.computeDocumentation()` returns **`@Nullable String`** html; may block on `completionItem/resolve` (not EDT).
- `LspCompletionObject.resolveCompletionItem()` is **`void` and blocking** (upstream `suspend`; rule would give `CompletableFuture<Void>`): cancellable via `checkCanceled`, must not be called on EDT. Only caller is `LspLookupElementDecorator`.
- `LspCompletionObject(LspClientImpl, java.util.concurrent.Semaphore, CompletionItem)`, `LspLookupElementDecorator(LspClientImpl, Semaphore, LookupElement, CompletionItem)`.
- `LspCompletionContributor.processCompletionItemsImpl(LspClientImpl, Document, int, CompletionResultSet, List<T>, Function<T, @Nullable LookupElement>)` static.
- `LspCompletionUtilKt.createCompletionContext(LspClientImpl, Document, int, boolean)`, `LspCompletionUtilKt.toCompletionList(Either<List<CompletionItem>, CompletionList>)` (used by `LspRequestExecutor`).
- `LspCompletionItemInsertHandler.INSTANCE`; `SnippetToTemplateConverter(Project, String)` with `computeTemplate(InsertionContext)`, `computeEffectiveLookup()`.
- `LspParameterInfoContext(SignatureHelp, LspClientImpl)` with `getSignatureHelp()/setSignatureHelp()/getClient()`.
- Expected from others (called by me): `LspClientImpl.getServerCapabilities()`, `getDescriptor()`, `getRequestExecutor()`, `getProject()`, `sendRequestSync(Function)`, `LspClientImpl.NOT_CANCELLABLE_REQUEST_TIMEOUT_MS`; `LspClientManagerImpl.getInstanceImpl(Project)`, `getClientsWithThisFileOpen(VirtualFile)`, `getClientsForFileRequests(VirtualFile)`; `LspServerCapabilitiesKt.supportsHover(LspClientImpl)`, `supportsSignatureHelp(LspClientImpl, VirtualFile)`, `getSignatureHelpTriggerCharacters(LspClientImpl, VirtualFile)`; `LspRequestExecutor.getHoverCaching(VirtualFile, int, int)`, `getCompletionList(VirtualFile, int, boolean)`, `getSignatureHelp(VirtualFile, int)`; `Lsp4jUtilKt.getLsp4jPosition/getRangeInDocument/applyTextEdits`; `LspCompletionSupport.expensiveRenderLookupElement(CompletionItem, LookupElementPresentation)` returning a `CompletableFuture<?>`; `LspCommandsSupport.executeCommand(LspClient, VirtualFile, Command)`.

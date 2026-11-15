# Consulo port

LSP client for Consulo, ported from JetBrains [intellij-community](https://github.com/JetBrains/intellij-community)
`platform/lsp` (API) and `platform/lsp-impl` (implementation), revision `23f185a58910962462881ebe8adca42ede651a8d`
(2026-11-09). The protocol library is Eclipse lsp4j 1.0.0, the same version JetBrains uses.

## Layout

| Upstream | Here | Maven module / JPMS module |
|---|---|---|
| `platform/lsp/src/api`, `platform/lsp/src/util` | `api/src/main/java/com/intellij/platform/lsp/{api,util}` | `consulo.lsp.api` (jar) / `consulo.lsp.api` |
| `platform/lsp-impl/src/impl` | `plugin/src/main/java/com/intellij/platform/lsp/impl` | `consulo.lsp` (consulo-plugin) / `consulo.lsp` |
| `platform/lsp-impl/structureView/src/impl/structureView` | `plugin/src/main/java/com/intellij/platform/lsp/impl/structureView` | same |
| `platform/lsp/resources/messages/LspBundle.properties` | `api/src/main/resources/LOCALIZE-LIB/en_US/com.intellij.platform.lsp.LspLocalize.yaml` | |
| `*.xml` plugin descriptors | `upstream/` (reference only, registrations are annotations) | |

- Packages and file names are the upstream ones. Each upstream `Foo.kt` becomes `Foo.java` in the same directory.
- Upstream files that are not converted yet stay as `Foo.kt_` (not compiled), so the whole upstream tree is kept and
  later upstream changes can be diffed file by file. Converting a file = add `Foo.java`, delete `Foo.kt_`.
- Upstream tests (`platform/lsp-impl/tests`) use the IntelliJ test framework and are not copied.
- Plugins depend on the `consulo.lsp` plugin and on the `consulo.lsp.api` artifact (`provided`). The impl packages are
  not exported.

## Kotlin to Java rules

The conversion is mechanical: same classes, same members, same logic, same comments (KDoc becomes Javadoc,
`[Foo]` becomes `{@link Foo}`). The upstream copyright line is kept as is. Consulo code style: 4 spaces, braces always,
`else`/`catch`/`finally` on a new line, `my` prefix for instance fields, `our` for static non-constant fields, no `var`,
interface types on the left (`List<X> x = new ArrayList<>()`).

- **Files.** Every public class/interface goes to its own `.java` file named after it. Top-level functions and
  properties of `Foo.kt` become `static` members of class `Foo` if `Foo.kt` declares a class `Foo`, otherwise of a
  `final` class `FooKt` with a private constructor (the JVM facade name Kotlin generates; `converters.kt` → `ConvertersKt`).
  Extension functions become static methods with the receiver as the first parameter.
- **Visibility.** `internal` → `public` (impl packages are not exported anyway). `private` top-level → `private static`.
- **Properties.** Kotlin JVM accessor names: `val foo` → `getFoo()`, `var foo` → `getFoo()`/`setFoo()`,
  `val isFoo` → `isFoo()`. `open val` → overridable method. `lateinit var`/`by lazy` → field + explicit init/lazy getter.
- **Objects.** `object Foo` with only functions → `final class Foo` with `static` members. An `object` that is a value
  (implements/extends a type, `data object`) → singleton with `public static final Foo INSTANCE`.
  `companion object` members → `static` members of the outer class.
- **Data classes** → final class with `getX()` accessors, `equals`/`hashCode`/`toString` (no records: accessors would be
  `x()`). `copy(...)` only if used.
- **Sealed classes** → abstract class / sealed interface, subclasses as nested `static final` classes.
- **Default parameters** → overloads for trailing defaults (like `@JvmOverloads`); a call that skips a middle parameter
  passes the default explicitly.
- **Function types**: `() -> Unit` → `Runnable`, `() -> T` → `Supplier<T>`, `(A) -> Unit` → `Consumer<A>`,
  `(A) -> Boolean` → `Predicate<A>`, `(A) -> R` → `Function<A, R>`, `(A, B) -> Unit` → `BiConsumer<A, B>`,
  `(A, B) -> R` → `BiFunction<A, B, R>`; other arities → a small `@FunctionalInterface` nested in the class that needs it.
  `fun interface` → `@FunctionalInterface interface`.
- **Nullability.** Modules are `@NullMarked`; `T?` → `@Nullable` (`org.jspecify.annotations.Nullable`). No `@Nonnull`.
- **Threading annotations.** `@RequiresReadLock` → `@RequiredReadAction`, `@RequiresWriteLock` → `@RequiredWriteAction`,
  `@RequiresEdt` → `@RequiredUIAccess` (`consulo.annotation.access` / `consulo.ui.annotation`);
  `@RequiresBackgroundThread`, `@RequiresReadLockAbsence` → dropped, keep the meaning in Javadoc if it documents a contract.
  `@ApiStatus.*`, `@NonNls`, `@Nls`, `@NlsSafe`, `@TestOnly`, `@VisibleForTesting` → dropped.
- **Coroutines.** `suspend fun foo(): T?` → `CompletableFuture<T>` (completed with `null` where upstream returns `null`).
  `cs.launch { }` / `withContext(Dispatchers.Default)` → `AppExecutorUtil.getAppExecutorService()` (or a bounded executor
  from `AppExecutorUtil.createBoundedApplicationPoolExecutor` where ordering matters); `delay`/debounce →
  `AppExecutorUtil.getAppScheduledExecutorService()`; `withContext(Dispatchers.EDT)` → `UIAccess`/`Application.invokeLater`;
  `readAction { }` → `ReadAction.compute`/`Application.runReadAction`. Multi-step EDT/read-lock pipelines may use Consulo
  coroutines (`consulo.util.concurrent.coroutine`). Cancellation semantics of upstream must be kept (cancel futures,
  `ProgressManager.checkCanceled()`).
- **Kotlin stdlib** (`map`, `filter`, `firstOrNull`, `buildList`, `use`, string templates, ...) → plain Java loops/streams.
  No Kotlin runtime dependency.
- **Logging.** `logger<Foo>()`/`Logger.getInstance` → `consulo.logging.Logger.getInstance(Foo.class)`.
- **Texts.** `LspBundle.message("a.b.c", x)` → `LspLocalize.aBC(x)` (`com.intellij.platform.lsp.localize.LspLocalize`,
  keys are lower-cased). Methods returning UI text return `LocalizeValue`. `LspBundle` itself is not ported.

## Registrations

Upstream XML registrations (see `upstream/*.xml`) become annotations on the class:

- extension point → `@ExtensionAPI(ComponentScope.APPLICATION)` on the interface, `EP_NAME` →
  `ExtensionPointName.create(Foo.class)`.
- extension → `@ExtensionImpl` (upstream `id`/`order` → `@ExtensionImpl(id = "...", order = "...")`). Upstream
  `language=""`/`language="any"` → `getLanguage()` returns `Language.ANY`.
- `projectService`/`applicationService`/`@Service` → `@ServiceAPI(ComponentScope.PROJECT|APPLICATION)` on the
  interface (or on the class itself for an internal service) + `@ServiceImpl` + `@Singleton` on the implementation, with
  an `@Inject` constructor. `getInstance(project)` → `project.getInstance(Foo.class)`. A `CoroutineScope` constructor
  parameter is replaced by `Disposable` + the executors above.
- `projectListeners`/`applicationListeners` → `@TopicImpl(ComponentScope.PROJECT|APPLICATION)`.
- `notificationGroup` → `NotificationGroup` constant + `@ExtensionImpl NotificationGroupContributor`.
- `registryKey` → `consulo.application.util.registry.Registry` keys read with the upstream default as fallback.

## Missing Consulo APIs

Consulo internal APIs (`*.internal.*` packages) are not used. IntelliJ APIs without a public Consulo equivalent are
ported with their original names to `consulo.lsp.compat` (api module, exported to the plugin) and listed here:

<!-- keep the list sorted; one line per API: `Upstream FQN` - what was done -->

## Stages

Stage 1 (core): API, server lifecycle and connectors (stdio, socket), document sync, server-to-client requests and
notifications, diagnostics (push + pull), hover, completion (+ resolve), definition / type definition / implementation,
references, document highlight, inlay hints, semantic tokens, code actions (+ resolve, `workspace/executeCommand`),
document symbols (structure view), signature help.

Later: rename, formatting (range / on type), folding, selection range, call / type hierarchy and inheritance markers,
code lens, document links / colors, workspace symbol, `workspace/willRenameFiles`, Node.js runtime manager,
Language Services status bar widget, Services view, LSP log.

Not implemented upstream either: declaration, `workspace/diagnostic`, semantic tokens range / delta, `inlayHint/resolve`,
color presentation, linked editing range, moniker, `willSave*`.

# Services view and Language Services widget conversion notes

Scope:

- Services view: `plugin/.../lsp/impl/serviceView/*` (upstream `platform/lsp-impl/src/impl/serviceView`).
- Language Services status bar widget, platform part (missing in Consulo, copied from IntelliJ with the original packages):
  `api/.../com/intellij/platform/lang/lsWidget/**` (upstream `platform/lang-api`) and
  `plugin/.../com/intellij/platform/lang/lsWidget/impl/**` (upstream `platform/lang-impl`).
- Language Services widget, LSP part: `api/.../lsp/api/lsWidget/*`, `plugin/.../lsp/impl/lsWidget/*`, and the restored
  `LspIntegrationProvider.createWidgetItems`/`createWidgetItem`, `LspServerSupportProvider.createLspWidgetItems`/
  `createLspServerWidgetItem` (+ their overrides).

Built with `mvn -q -B package -Dmaven.test.skip=true -Dmaven.build.cache.enabled=false` (NullAway on).

## 1. Files

| Upstream | Here |
|---|---|
| `LspServiceViewContributor.kt` | `LspServiceViewContributor` (+ nested `LspRootDescriptor`) |
| `LspServiceViewSupport.kt` | `LspServiceViewSupport` (project service) |
| `LspClientServiceViewDescriptor.kt` | `LspClientServiceViewDescriptor` |
| `LspClientConsole.kt` | `LspClientConsole` (top-level `trafficPayloadPreview` -> static member) |
| `LspServiceViewActions.kt` | `LspServiceViewActionsKt` (`LSP_CLIENT_DATA_KEY`, `getLspClientServiceViewActionGroup()`, the private Restart/Stop actions), `LspServerLog` (+ `Location`), `ShowLspServerLogAction`, `ShowLspServerLogWidgetAction` |
| `LspTrafficPayloadPopup.kt` | `LspTrafficPayloadHyperlinkInfo`, `LspTrafficPayloadPopupKt` (`buildPayloadPopupText`) |
| `LanguageServiceWidgetItem.kt` | `LanguageServiceWidgetItem`, `LanguageServicePopupSection`, `LanguageServiceItemRunningState`, `OpenSettingsAction` |
| `LanguageServiceWidgetItemsProvider.kt`, `internal/LanguageServiceWidgetActionsService.kt` | same names |
| `LanguageServiceWidget.kt`, `LanguageServiceWidgetFactory.kt`, `LanguageServiceWidgetActionsServiceImpl.kt` | same names; `LANGUAGE_SERVICES_WIDGET_ID` is a constant of the factory |
| `fus/LanguageServiceWidgetUsagesCollector.kt` | deleted: Consulo has no FUS; the `actionInvoked` calls in `LanguageServiceWidgetActionsServiceImpl` / `LspWidgetInternalServiceImpl` are dropped |
| `LspClientWidgetItem.kt`, `LspServerWidgetItem.kt`, `LspWidgetInternalService.kt` | same names (`LspClientWidgetItem.RestartLspClientAction`/`StopLspClientAction` nested) |
| `LspWidgetItemsProvider.kt`, `LspWidgetInternalServiceImpl.kt` | same names (top-level `openLspServerErrorOutput` -> static member of `LspWidgetInternalServiceImpl`) |
| `LangBundle` `language.services.*` keys | `api/src/main/resources/LOCALIZE-LIB/en_US/com.intellij.platform.lang.LangLocalize.yaml` -> `com.intellij.platform.lang.localize.LangLocalize` (exported) |

The `// TODO serviceView stage` call sites are wired as upstream: `Lsp4jServerConnector.printTrafficSafely`
(`LspServiceViewSupport.getInstanceIfCreated`), `LspServerProcessListenerBase` (`printProcessOutput`, `flushProcessOutput`),
`LspServerNotificationsHandlerImpl` (`printShowMessage`, `printLogMessage`, `printTrace` - the interim `LOG.debug` of `logTrace`
is removed as upstream; `ShowLspServerLogAction` on error/warning notifications when `offerServerLogInNotifications`),
`LspClientManagerImpl.ensureStarted` (`LspServiceViewSupport.getInstance(project)` before the first `clientAdded`).

## 2. Registrations

| Upstream | Here |
|---|---|
| `<serviceViewContributor implementation="...LspServiceViewContributor"/>` | `@ExtensionImpl` on `LspServiceViewContributor` (`consulo.execution.service.ServiceViewContributor`) |
| `@Service(PROJECT) LspServiceViewSupport` | `@ServiceAPI(PROJECT)` + `@ServiceImpl` + `@Singleton` on the class, `Disposable` |
| EP `com.intellij.platform.lang.lsWidget.itemsProvider` | `@ExtensionAPI(ComponentScope.APPLICATION)` on `LanguageServiceWidgetItemsProvider` (`EP_NAME = ExtensionPointName.create(...)`) |
| `<platform.lang.lsWidget.itemsProvider id="LspWidgetItemsProvider"/>` | `@ExtensionImpl(id = "LspWidgetItemsProvider")` |
| `<statusBarWidgetFactory id="LanguageServiceStatusBarWidget" order="after Position, after AIAssistant, before LineSeparator"/>` | `@ExtensionImpl(id = "LanguageServiceStatusBarWidget", order = "after positionWidget, before lineSeparatorWidget")` on `LanguageServiceWidgetFactory extends StatusBarEditorBasedWidgetFactory` |
| `applicationService LanguageServiceWidgetActionsService` / `LspWidgetInternalService` | `@ServiceAPI(APPLICATION)` on the abstract classes (api), `@ServiceImpl @Singleton` implementations (plugin) |
| `notificationGroup language.service.stopped.or.restarted` (BALLOON, not logged) | `LanguageServiceNotificationGroupContributor` (`SERVICE_STOPPED_OR_RESTARTED_GROUP`) |

module-info: api exports `com.intellij.platform.lsp.api.lsWidget`, `com.intellij.platform.lang.lsWidget`,
`com.intellij.platform.lang.localize`, and `com.intellij.platform.lang.lsWidget.internal` to `consulo.lsp`; it now
`requires transitive consulo.configurable.api`. The plugin requires `consulo.execution.api` (Services view, console),
`consulo.external.service.api` (`PluginsConfigurable`) and `consulo.language.editor.ui.api` (`AWTLanguageEditorUtil.getEditorFont`);
the poms have the matching `provided` dependencies.

## 3. Missing Consulo APIs and deviations

- **FUS** (`LanguageServiceWidgetUsagesCollector`) - none in Consulo; the file is deleted, the calls are dropped.
- **`ExperimentalUI`** - Consulo has no old/new UI switch. `LanguageServiceWidgetItem.createWidgetAction` always takes the
  upstream new-UI branch: inline actions via `ActionUtil.INLINE_ACTIONS` (Consulo's action popups render them).
- **`EditorBasedStatusBarPopup`** - Consulo's one has no `createComponent()` (the widget is drawn from its presentation), so
  `LanguageServiceTextPanel` (fixed accessible name "Language Services Button") is not ported; the key stays in `LangLocalize`.
  Its constructor takes the factory instead of a `CoroutineScope`; `myConnection` is created in `install`, so the item updaters
  are registered after `super.install(statusBar)` with the widget itself as the parent disposable (upstream: before, with
  `myConnection`). `getWidgetState` runs in a non-blocking read action (as upstream: BGT).
- **Icons** - `IconManager.withIconBadge(icon, JBUI.CurrentTheme.IconBadge.SUCCESS/WARNING)` -> `BadgeIconSupplier.withIconBadge(icon,
  ComponentColors.ICON_BADGE_SUCCESS/WARNING)`; `withLiveIndicatorIcon` -> `ExecutionUtil.getIconWithLiveIndicator`;
  `IconLoader.getDisabledIcon` -> `ImageEffects.grayed`; `LayeredIcon.layeredIcon` -> `ImageEffects.layered`; `RowIcon` ->
  `ImageEffects.appendRight`; `EmptyIcon.create(3, 16)` -> `ImageEffects.empty(3, 16)`; `IconUtil.colorize` -> `ImageEffects.colorize`.
  `AllIcons.Json.Object` (default item icon and the widget icon without current-file items) -> `PlatformIconGroup.webreferencesServer()`
  (the server icon of the Services view; `nodesStruct()` used elsewhere for `Json.Object` looks wrong colorized in the status bar).
  `AllIcons.Actions.StopAndRestart` (widget Stop and Restart) -> `actionsRestart()`; Services view Restart/Stop -> `actionsRestart()`/`actionsSuspend()`;
  `Toolwindows.ToolWindowMessages` -> `toolwindowsToolwindowmessages()`; `Webreferences.Server` -> `webreferencesServer()`.
- **Status bar icon color** - no `JBUI.CurrentTheme.StatusBar.Widget.FOREGROUND` / `LafManager`: `ComponentColors.TEXT_FOREGROUND`
  in dark styles and `ComponentColors.INFO_FOREGROUND` in light ones (upstream: `FOREGROUND.brighter()` in light themes).
- **More Languages...** - Consulo's plugin settings can't search the Marketplace by tag (`/tag:"Language Server"`); the action opens
  `PluginsConfigurable` (`ShowSettingsUtil.showAndSelect`).
- **Open settings** - `ShowSettingsUtil.showSettingsDialog(project, Class)` -> `ShowSettingsUtil.showAndSelect(project, settingsPageClass)`;
  the settings page class type is `Class<? extends consulo.configurable.Configurable>` (the configurable must be registered,
  e.g. a `ProjectConfigurable` `@ExtensionImpl`).
- **`StatusBar.Info.set`** (`LspServerLog.show` when the log is not available) - no public status bar text API in Consulo
  (`StatusBarEx` is internal); the text is shown as an INFORMATION notification of the `LSP messages` group.
- **`serviceOrNull<ServiceViewManager>()`** - the Services tool window is a part of the Consulo platform, `LspServerLog.show`
  always uses `ServiceViewManager` (`find(servicesViewAvailable = true)`).
- **`IdeProductMode.isFrontend`** (`LspClientWidgetItem.widgetActionLocation`) - always `false` in Consulo.
- **`StringUtil.collapseWhiteSpace`** - missing in Consulo `StringUtil`; a private copy in `LspClientConsole`.
- **`ActionUpdateThread.BGT`** - actions with `update` implement `AnActionWithSyncUpdate` (always updated in background).
- **`DataKey`/`UiDataProvider`** - `LSP_CLIENT_DATA_KEY` is a `consulo.util.dataholder.Key`; `LspClientServiceViewDescriptor`
  implements `consulo.dataContext.UiDataProvider` (Consulo's `BaseServiceView` asks the descriptor the same way).
- **Texts** - methods returning UI text return `LocalizeValue` (`LanguageServiceWidgetItem.getStatusBarTooltip`,
  `LspClientWidgetItem.getWidgetActionText`); labels built from server data (`getItemLabel`, `getVersionPostfix`, ...) stay `String`.
- **Tests-only API** - `LspClientConsole.getConsoleTextForTests`/`getConsoleViewForTests` (`ConsoleViewImpl` is internal) not ported.
- **`LspTrafficPayloadHyperlinkInfo`** - the popup is Swing (`JBTextArea` in `JBScrollPane`, editor font from
  `AWTLanguageEditorUtil.getEditorFont()`), as upstream; `JBPopupFactory.createComponentPopupBuilder` takes the Swing component.

## 4. Behaviour notes (seen in the sandbox)

- With only the LSP contributor in the Services tool window, Consulo's `ServiceViewModel` shows the clients directly (no
  "Language Servers (LSP)" root node; `ServiceViewModel.createModel` builds a `ContributorModel` for a single contributor root,
  as IntelliJ does). The root node with the "Language Servers (LSP)" name is expected next to other contributors (not tried).
- Stopping or restarting a client removes it from the Services view (`clientRemoved`); when the view becomes empty the tool
  window hides, and the restarted client does not re-activate it (`ServiceViewNonActivatingDescriptor`, as upstream).
- In the widget popup, hovering an inline icon near its left edge selected the previous inline action (platform popup, not investigated).

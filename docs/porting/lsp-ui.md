# lsp.ui frontend (LSP Servers settings)

Scope: `plugin/src/main/java/com/intellij/lsp/ui/frontend/**` (upstream `plugins/lsp.ui/lsp.ui.frontend/src`) and the texts
`upstream/LspUiBundle.properties`. Every `.kt_` of the scope is converted or deleted (none left).
Build: `mvn -q -B package -Dmaven.test.skip=true -Dmaven.build.cache.enabled=false` - clean (NullAway on).

## 1. Files

| Upstream | Here |
|---|---|
| `ConfigurableLspClientDescriptor.kt` | `ConfigurableLspClientDescriptor.java` |
| `ConfigurableLspIntegrationProvider.kt` | `ConfigurableLspIntegrationProvider.java` |
| `settings/LspServerConfiguration.kt` (3 classes) | `settings/LspServerConfiguration.java`, `settings/LspIntegrationSettingsImpl.java` (with nested `PluginServerState`), `settings/EnvironmentVariablesDataOptions.java` |
| `settings/LspServersConfigurable.kt` | `settings/LspServersConfigurable.java` (nested `LspServerNamedConfigurable`, `AddAction`, `MyDeleteAction`) |
| `settings/LspServerConfigurable.kt` | `settings/LspServerConfigurable.java` (top-level `parseSocketPortArgument`/`replaceCommunicationModeArgument` are static members) |
| `settings/PluginLspServerConfigurable.kt` (2 classes) | `settings/PluginLspServerConfigurable.java`, `settings/PluginLspServerNamedConfigurable.java` |
| `settings/LspPatternsPanel.kt` | `settings/LspPatternsPanel.java` |
| `LspUiBundle.kt` | deleted; texts in `api/src/main/resources/LOCALIZE-LIB/en_US/com.intellij.lsp.ui.frontend.LspUiLocalize.yaml` (all keys of `LspUiBundle.properties`, lower-cased), generated `com.intellij.lsp.ui.frontend.localize.LspUiLocalize`, package exported from the api `module-info` |
| `LspConfiguredPluginAdvertiserSuppressor.kt` | deleted - Consulo has no plugin advertiser suppressor EP (`PluginAdvertiserExtension` only contributes extension previews, it can't suppress a file) |
| `internal/ConfigureIntelliJFrankensteinMonsterAction.kt` | deleted - internal JetBrains dogfooding action (IntelliJ language server) |
| `statistics/LspServerUsagesCollector.kt` | deleted - no FUS in Consulo |
| - | new `LspUiNotificationGroupContributor.java`, `settings/LspIntegrationSettingsExtension.java` (see 2) |

## 2. Registrations (`upstream/intellij.lsp.ui.frontend.xml`)

| Upstream | Consulo |
|---|---|
| `projectConfigurable id="lsp.servers" parentId="language"` | `@ExtensionImpl LspServersConfigurable implements ProjectConfigurable, SearchableConfigurable`, `getId() = "lsp.servers"`, `getParentId() = StandardConfigurableIds.EDITOR_GROUP` - Consulo has no "Languages & Frameworks" group, so the page is **Settings / Editor / LSP Servers** |
| `platform.lsp.integrationProvider` | `@ExtensionImpl ConfigurableLspIntegrationProvider` |
| `projectService serviceInterface=LspIntegrationSettings serviceImplementation=LspIntegrationSettingsImpl` | `LspIntegrationSettingsImpl` is `@ServiceAPI(PROJECT) @ServiceImpl @Singleton` (holds the `@State`) + `@ExtensionImpl LspIntegrationSettingsExtension implements LspIntegrationSettings` delegating to it. Reason: in this port `LspIntegrationSettings` is a project `@ExtensionAPI` (optional service), and the Consulo component store loads `PersistentStateComponent` state only for services, not for extensions |
| `notificationGroup id="Language Servers"` | `LspUiNotificationGroupContributor.NOTIFICATION_GROUP` (`@ExtensionImpl NotificationGroupContributor`); only the deleted internal action used it upstream |
| `pluginAdvertiserSuppressor`, `statistics.projectUsagesCollector`, `ConfigureIntelliJMonster` action | not ported (see 1) |

## 3. Persistent state

`@State(name = "LspServerSettings", storages = @Storage(value = StoragePathMacros.WORKSPACE_FILE, roamingType = RoamingType.DISABLED))`
(Consulo has no `RoamingType.LOCAL`; the workspace file isn't roamed anyway). `getState()` returns the service itself and
`loadState` uses `XmlSerializerUtil.copyBean` like upstream. The Consulo serializer binds public getter/setter pairs, so the
annotations are on the getters: `XCollection(style = v2)` -> `@Tag + @AbstractCollection(surroundWithTag = false)`,
`XMap` -> `@MapAnnotation(surroundWithTag = false, surroundKeyWithTag = false, sortBeforeSave = false, ...)`.
`opens com.intellij.lsp.ui.frontend.settings to consulo.util.xml.serializer` in the plugin `module-info`. Result:

```xml
<component name="LspServerSettings">
  <servers>
    <server executablePath="/opt/dart-sdk/bin/dart" name="Dart">
      <arguments>language-server</arguments>
      <filePatterns>*.dart</filePatterns>
    </server>
  </servers>
</component>
```

`EnvironmentVariablesDataOptions` extends `BaseState` upstream (no Consulo equivalent): plain bean with `equals`/`hashCode`,
tag `environment` (upstream `@Tag("")`). `LspServerConfiguration` is a data class upstream: final class with `copy()`
(shallow, `envVars` shared - as upstream), `equals`/`hashCode`/`toString`; String setters accept `null` from the serializer.

## 4. UI

- `MasterDetailsComponent` / `NamedConfigurable` - Consulo has both (`consulo.ui.ex.awt.MasterDetailsComponent`,
  `consulo.configurable.NamedConfigurable`); the master/details part stays Swing (the platform component is Swing), the
  details are unified UI. `MasterDetailsComponent` needs `Provider<MasterDetailsStateService>` (constructor injection).
- `BoundConfigurable` + Kotlin UI DSL -> `SimpleConfigurableByProperties` with `consulo.ui` components
  (`CheckBox`, `TextBox`, `RadioGroup`, `FormBuilder`, `LabeledLayout` for `group(...)`, `Hyperlink`); every
  `bind*`/`onApply`/`onIsModified`/`onReset` is a `PropertyBuilder` property; the panel `onApply` (socket port) is in an
  `apply(LayoutWrapper)` override after `super.apply`. `comment(...)`/`contextHelp(...)` -> a grayed `Label` under the component.
- Radio buttons: upstream relies on action listeners (user-only); here the reset sets the `RadioGroup` value without
  listeners, so the group value listener only reacts to the user.
- `TextFieldWithBrowseButton` -> `FileChooserTextBoxBuilder`. `EnvironmentVariablesTextFieldWithBrowseButton` -> Consulo's
  (`consulo.execution.ui.awt`, unified component, no project parameter). `NetUtils.findAvailableSocketPort` -> `consulo.util.io.NetUtil`.
- `LspPatternsPanel`: upstream `JPanel` + `JBList` + `ToolbarDecorator` -> `PseudoComponent` over a unified `ListBox`
  decorated with `ToolbarDecoratorBuilderFactory` (add/edit/remove). `ListBox` has no empty text, so
  `lsp.settings.server.file.patterns.empty` is unused. `Messages.showInputDialog` -> async `InputBoxBuilder.text()`.
- Delete confirmation `Messages.showYesNoDialog` -> async `Alerts.yesNo()`.
- Initialization options editor: upstream creates a `JsonLanguage` PSI file and a `TemplateEditorUtil` editor
  (`TemplateEditorUtil` is internal in Consulo, JSON is a separate plugin). Here: `Language.findLanguageByID("JSON")` or
  plain text, `PsiFileFactory.createFileFromText("dummy.json", ...)` and `EditorFactory.createEditor(document, project, file, false)`.
- "More via plugins..." link: Consulo's plugin manager has no marketplace tag search (`/tag:"Language Server"`); the link
  selects `PluginsConfigurable.CONFIGURABLE_ID` in the open settings dialog (or opens settings on `PluginsConfigurable`).
  "Provided by" link of a plugin server: `ShowSettingsUtil.showAndSelect(project, PluginsConfigurable.class, c -> c.selectInstalled(pluginId))`.
- `LspIntegrationSettingsProvider.EP_NAME.processWithPluginDescriptor` -> `getExtensionList()` + `PluginManager.getPlugin(provider.getClass())`.
- `ActionUpdateThread.EDT` (delete action) -> `AnActionWithSyncUpdate` (as the platform `MasterDetailsComponent.MyDeleteAction`).
- Disposal: upstream registers node configurables in the configurable itself and disposes it in `disposeUIResources()`.
  The Consulo configurable is a reused project extension, so a child `Disposable` is created lazily and disposed there.
- `MasterDetailsComponent` calls `reInitWholePanelIfNeeded()` from its constructor, before subclass fields exist - the
  override is null-safe and adds the "More via plugins" panel lazily.

## 5. Missing Consulo APIs / dropped

- Eel (remote environments): `project.getEelDescriptor()` is always local - socket mode always supported, the
  `UniversalFileChooser` branch for remote projects and `getRemoteProjectBaseNioPath()` are not ported (comments in place).
- `Configurable.WithEpDependencies` - no equivalent (dynamic plugins).
- `LspBulkIntegrationProvider.createWidgetItem` (`LspClientWidgetItem` with `settingsPageClass`) - `LspIntegrationProvider`
  has no widget item methods yet (lsWidget stage); TODO comment in `ConfigurableLspIntegrationProvider`.
- No "Languages & Frameworks" settings group (see 2).

## 6. Verified (sandbox, Xvfb)

Settings / Editor / LSP Servers shows the page; added server `Dart`, executable `/opt/dart-sdk/bin/dart`, arguments
`language-server`, pattern `*.dart`; stdio/socket switch rewrites `--stdio` / `--socket=<port>` in the arguments; Apply.
Opening `bin/main.dart` starts the server (`ConfigurableLspClientDescriptor@ui-dartproj ... LSP server initialized`,
Dart SDK LSP Analysis Server 3.13.7) with diagnostics, inlay hints and semantic highlighting. After File / Close Project
the state is in `.consulo/workspace.xml` (above); reopening the project restores the page and starts the server again.
The delete confirmation dialog shows the server name. A standalone serializer round trip (servers + plugin server state
with arguments, patterns and environment) was also checked.

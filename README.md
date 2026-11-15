# consulo-lsp

Language Server Protocol client for [Consulo](https://consulo.io), ported from the JetBrains IntelliJ Platform LSP client
(`platform/lsp`, `platform/lsp-impl`). Language plugins use it to connect a language server to the editor:
diagnostics, semantic highlighting, inlay hints, hover, completion, navigation, references, document highlight,
structure view, code actions and more. See [CONSULO.md](CONSULO.md) for the port layout, the conversion rules and
the list of missing platform APIs.

## Using it from a plugin

Add the API artifact and depend on the plugin:

```xml
<dependency>
    <groupId>consulo.plugin</groupId>
    <artifactId>consulo.lsp.api</artifactId>
    <version>${project.version}</version>
    <scope>provided</scope>
</dependency>
```

```xml
<!-- META-INF/plugin.xml -->
<depends>consulo.lsp</depends>
```

```java
// module-info.java
requires consulo.lsp.api;
```

Register an `LspIntegrationProvider` and describe the server with an `LspClientDescriptor`:

```java
@ExtensionImpl
public class FooLspIntegrationProvider implements LspIntegrationProvider {
    @Override
    public void fileOpened(Project project, VirtualFile file, LspClientStarter clientStarter) {
        if ("foo".equals(file.getExtension())) {
            clientStarter.ensureClientStarted(new FooLspClientDescriptor(project));
        }
    }
}

public class FooLspClientDescriptor extends ProjectWideLspClientDescriptor {
    public FooLspClientDescriptor(Project project) {
        super(project, "Foo");
    }

    @Override
    public boolean isSupportedFile(VirtualFile file) {
        return "foo".equals(file.getExtension());
    }

    @Override
    public GeneralCommandLine createCommandLine() throws ExecutionException {
        return new GeneralCommandLine("foo-language-server", "--stdio");
    }
}
```

The API is the JetBrains one with the Java names Kotlin generates (`getLspCustomization()`, `getLsp4jServerClass()`,
...), so the [JetBrains LSP documentation](https://plugins.jetbrains.com/docs/intellij/language-server-protocol.html)
applies. Suspending functions return `CompletableFuture`. The deprecated names (`LspServerSupportProvider`,
`LspServerDescriptor`, `ProjectWideLspServerDescriptor`, `LspServerManager`, `LspServer`) are kept for code written
against older IntelliJ versions.

- Per-feature switches: override `getLspCustomization()` and return `LspXxxDisabled.INSTANCE` or a customized
  `LspXxxSupport` from the matching getter.
- Custom requests / notifications to the server: an interface extending `Lsp4jServer` with lsp4j `@JsonRequest` /
  `@JsonNotification` methods, returned from `getLsp4jServerClass()`; call it with `LspClient.sendRequest` /
  `sendRequestSync` / `sendNotification`.
- Custom server-to-client messages: a subclass of `Lsp4jClient` returned from `createLsp4jClient(handler)`.
- A server that is already running (for example an in-process bridge): return
  `new LspCommunicationChannel.Socket(port, false)` from `getLspCommunicationChannel()`, and start the client with
  `LspClientManager.getInstance(project).ensureClientStarted(FooLspIntegrationProvider.class, descriptor)` instead of
  waiting for `fileOpened`.

## Building

```bash
mvn package
```

The plugin is assembled in `plugin/target/consulo-plugin-extracted`. `mvn consulo:run-desktop-awt-fork -pl :consulo.lsp`
starts a sandbox with it.

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import com.intellij.platform.lsp.api.customization.LspCustomization;
import consulo.annotation.access.RequiredReadAction;
import consulo.application.Application;
import consulo.logging.Logger;
import consulo.lsp.compat.OSProcessHandler;
import consulo.process.BaseProcessHandler;
import consulo.process.ExecutionException;
import consulo.process.cmd.GeneralCommandLine;
import consulo.process.io.BaseDataReader;
import consulo.process.io.BaseOutputReader;
import consulo.project.Project;
import consulo.util.dataholder.Key;
import consulo.util.io.OSAgnosticPathUtil;
import consulo.util.io.URLUtil;
import consulo.util.lang.StringUtil;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.VirtualFileManager;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ClientInfo;
import org.eclipse.lsp4j.ConfigurationItem;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Defines how the {@link LspClient} interacts with the running LSP server
 * and how to start the LSP server process ({@link #startServerProcess}, {@link #createCommandLine}, and {@link #getLspCommunicationChannel}).
 * <p>
 * See {@link LspIntegrationProvider} documentation for details about starting a {@code LspClient}.
 * <p>
 * Plugins that want to run a single LSP client (and server) for the whole project, regardless of the project structure, should extend
 * {@link ProjectWideLspClientDescriptor}.
 * <p>
 * Normally, {@code LspClientDescriptor} implementations don't store any modifiable state.
 * <p>
 * As a rule, plugins don't keep references to the {@code LspClientDescriptor} implementations. To get an {@code LspClientDescriptor} that is used to
 * start a specific LSP server, use {@link LspClient#getDescriptor()}, where the {@link LspClient} itself could be found using
 * {@link LspClientManager#getClients}.
 * <p>
 * {@code presentableName}: this string may appear in the UI in some cases, for example:
 * <ul>
 * <li>{@code Language Services} status bar widget item</li>
 * <li>{@code Show Usages} popup header</li>
 * <li>LSP-related information in the {@code Notifications} tool window</li>
 * </ul>
 */
public abstract class LspClientDescriptor {
    public static final Logger LOG = Logger.getInstance(LspClientDescriptor.class);

    /**
     * The URI the {@link LspClient} requested the content of a dynamic file with, see {@link #getFileUri}.
     */
    public static final Key<String> DYNAMIC_FILE_URI = Key.create("lsp.dynamic.file.uri");

    private static final List<Map.Entry<String, String>> FILE_NAME_ENDING_TO_LANGUAGE_ID = List.of(
        Map.entry(".blade.php", "blade")
    );

    // https://microsoft.github.io/language-server-protocol/specifications/specification-current/#textDocumentItem
    // Language ID is usually equal to the lowercased file extension.
    // This map tracks only non-standard cases.
    @SuppressWarnings("SpellCheckingInspection")
    private static final Map<String, String> FILE_EXTENSION_TO_LANGUAGE_ID = Map.ofEntries(
        Map.entry("js", "javascript"),
        Map.entry("cjs", "javascript"),
        Map.entry("mjs", "javascript"),
        Map.entry("jsx", "javascriptreact"),
        Map.entry("ts", "typescript"),
        Map.entry("tsx", "typescriptreact"),

        Map.entry("fs", "fsharp"),
        Map.entry("fsx", "fsharp"),
        Map.entry("handlebars", "handlebars"),
        Map.entry("pcss", "postcss"),
        Map.entry("phtml", "php"),
        Map.entry("pug", "jade"),
        Map.entry("py", "python"),
        Map.entry("rb", "ruby"),
        Map.entry("tpl", "smarty")
    );

    private final Project myProject;
    private final String myPresentableName;
    private final VirtualFile[] myRoots;

    private final LspCustomization myLspCustomization = new LspCustomization();

    protected LspClientDescriptor(Project project, String presentableName, VirtualFile... roots) {
        myProject = project;
        myPresentableName = presentableName;
        myRoots = roots;
    }

    public Project getProject() {
        return myProject;
    }

    public String getPresentableName() {
        return myPresentableName;
    }

    public VirtualFile[] getRoots() {
        return myRoots;
    }

    /**
     * Helps to read logs. By the way, implementations can use {@link #LOG} property defined in this class if they want.
     */
    @Override
    public String toString() {
        return getClass().getSimpleName() + "@" + myProject.getName();
    }

    /**
     * Implementations should return {@code true} if the LSP server needs to track the file contents while the file is being edited.
     * In other words, {@code true} means that the server needs to know the maybe-not-yet-saved state of the file at any moment of time.
     * In this case the IDE will take care of sending the {@code didOpen}, {@code didChange}, and {@code didClose} notifications to the server
     * according to the
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_synchronization">specification</a>.
     * <p>
     * If implementation returns {@code false} then the server will only know the contents of the file as it is stored on the disk.
     * <p>
     * The implementation must be idempotent, have no side effects,
     * and the result must depend only on the given file but not on the project settings or whatever else.
     * The call site has the right to cache the returned result.
     * <p>
     * Typical implementation:
     * <pre>
     *   &#64;Override
     *   public boolean isSupportedFile(VirtualFile file) {
     *     return "foo".equals(file.getExtension());
     *   }
     * </pre>
     * or
     * <pre>
     *   &#64;Override
     *   public boolean isSupportedFile(VirtualFile file) {
     *     return file.getFileType() == FooFileType.INSTANCE;
     *   }
     * </pre>
     *
     * @param file the file is guaranteed to be valid and within project content roots. It may be located on a remote
     *             execution host and might not be within the roots configured for this LSP server, which is usually fine.
     */
    @RequiredReadAction
    public abstract boolean isSupportedFile(VirtualFile file);

    /**
     * Starts the LSP server process.
     * Usually, plugins don't need to override this function, but only implement the {@link #createCommandLine} function.
     * <p>
     * Called in a background thread. The process is always started locally.
     */
    public BaseProcessHandler startServerProcess() throws ExecutionException {
        // LSP spec says: "It defaults to utf-8, which is the only encoding supported right now"
        // see https://microsoft.github.io/language-server-protocol/specification/#contentPart
        GeneralCommandLine commandLine = createCommandLine().withCharset(StandardCharsets.UTF_8);

        LOG.info(this + ": starting LSP server: " + commandLine);
        return new OSProcessHandler(commandLine) {
            @Override
            protected BaseOutputReader.Options readerOptions() {
                return lspReaderOptions();
            }
        };
    }

    private static BaseOutputReader.Options lspReaderOptions() {
        return new BaseOutputReader.Options() {
            @Override
            public BaseDataReader.SleepingPolicy policy() {
                return forMostlySilentProcess().policy();
            }

            // Must not lose '\r' in "\r\n" line endings. They affect char count, which must match `Content-Length`
            @Override
            public boolean splitToLines() {
                return false;
            }
        };
    }

    /**
     * The command line to start the server.
     * Each plugin must implement this function, unless:
     * <ul>
     * <li>the plugin implements the more generic function {@link #startServerProcess}</li>
     * <li>the plugin uses {@link LspCommunicationChannel.Socket} with {@code startProcess} set to {@code false}</li>
     * </ul>
     * Called in a background thread.
     */
    public GeneralCommandLine createCommandLine() throws ExecutionException {
        // this text goes only to the IDE logs
        throw new ExecutionException("createCommandLine() function not implemented");
    }

    /**
     * The channel for the communication between the IDE and the LSP server: StdIO or Socket
     */
    public LspCommunicationChannel getLspCommunicationChannel() {
        return LspCommunicationChannel.StdIO.INSTANCE;
    }

    /**
     * Returns a <a href="https://microsoft.github.io/language-server-protocol/specification/#documentUri">DocumentUri</a>, which can be used in various
     * requests to the LSP server.
     * The default implementation simply calls {@link #getFilePath} and converts it to {@code file://...} URI.
     * For a dynamic file, an in-memory file the {@link LspClient} created from a {@code workspace/textDocumentContent} result,
     * it returns the URI the content was requested with.
     * For a UNC path, it produces a {@code file://host/share/path} URI, which keeps the host in the authority.
     */
    public String getFileUri(VirtualFile file) {
        String dynamicFileUri = file.getUserData(DYNAMIC_FILE_URI);
        return dynamicFileUri != null ? dynamicFileUri : localFileUri(getFilePath(file));
    }

    private static String localFileUri(String path) {
        String uncFileUri = uncFileUri(path);
        if (uncFileUri != null) {
            return uncFileUri;
        }
        String escapedPath = URLUtil.encodePath(path);
        String url = VirtualFileManager.constructUrl(URLUtil.FILE_PROTOCOL, escapedPath);
        URI uri = VirtualFileUtil.toUri(url);
        return lowercaseWindowsDriveAndEscapeColon(uri != null ? uri.toString() : url);
    }

    /**
     * Puts the host of a UNC path in the authority of the URI, as <a href="https://datatracker.ietf.org/doc/html/rfc8089#section-2">RFC 8089</a>
     * and VS Code do: the {@code //wsl.localhost/Ubuntu/project} path becomes {@code file://wsl.localhost/Ubuntu/project}.
     *
     * @return the URI of a UNC {@code path}, or {@code null} when {@code path} is not a UNC path or its host cannot be an authority
     */
    private static @Nullable String uncFileUri(String path) {
        if (!path.startsWith("//")) {
            return null;
        }
        int hostEnd = path.indexOf('/', 2);
        String host = path.substring(2, hostEnd >= 0 ? hostEnd : path.length());
        if (!isUriHost(host)) {
            return null;
        }
        // The encoded path still starts with `//`, so it becomes the authority and the path of the URI
        return URLUtil.FILE_PROTOCOL + ":" + URLUtil.encodePath(path);
    }

    /**
     * A device path such as {@code //./pipe/x} or {@code //?/C:/dir} has no host.
     * A WebDAV host such as {@code host@SSL@443}, a port, an IPv6 literal or a backslash separator changes its meaning in an authority.
     * Such a path keeps the plain {@code file:////host/share} conversion, which RFC 8089 also allows.
     */
    private static boolean isUriHost(String host) {
        if (host.isEmpty() || host.startsWith(".")) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if ("\\?#@:[]%<>^|".indexOf(c) >= 0 || Character.isWhitespace(c) || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The LSP spec <a href="https://microsoft.github.io/language-server-protocol/specification/#uri">requires</a>
     * that all servers work fine with URIs in both formats: {@code file:///C:/foo} and {@code file:///c%3A/foo}.
     * <p>
     * VS Code always sends a lowercased Windows drive letter and always escapes colon
     * (see this <a href="https://github.com/microsoft/vscode-languageserver-node/issues/1280">issue</a>
     * and the related <a href="https://github.com/microsoft/language-server-protocol/pull/1786">pull request</a>).
     * <p>
     * Some LSP servers support only the VS Code-friendly URI format ({@code file:///c%3A/foo}), so it's safer to use it by default.
     */
    private static String lowercaseWindowsDriveAndEscapeColon(String uri) {
        String prefix = "file:///";
        if (uri.startsWith(prefix) && OSAgnosticPathUtil.startsWithWindowsDrive(uri.substring(prefix.length()))) {
            return prefix + Character.toLowerCase(uri.charAt(prefix.length())) + "%3A" + uri.substring(prefix.length() + 2);
        }
        return uri;
    }

    /**
     * @see #getFileUri
     */
    public String getFilePath(VirtualFile file) {
        return file.getPath();
    }

    /**
     * Extracts a file path from {@code fileUri} and calls {@link #findLocalFileByPath}.
     * Respects only {@code file://...} URIs.
     * A host in the authority gives a UNC path, symmetric to {@link #getFileUri}.
     *
     * @param fileUri a <a href="https://microsoft.github.io/language-server-protocol/specification/#documentUri">DocumentUri</a> received from the LSP
     *                server within some response or notification
     */
    public @Nullable VirtualFile findFileByUri(String fileUri) {
        try {
            URI uri = new URI(fileUri);
            if (!URLUtil.FILE_PROTOCOL.equals(uri.getScheme())) {
                LOG.warn("Unexpected URI scheme: " + fileUri);
                return null;
            }
            String uriPath = uri.getPath();
            if (uriPath == null) {
                LOG.warn("Unexpected URI (no path): " + fileUri);
                return null;
            }
            String authority = uri.getAuthority();
            if (StringUtil.isEmpty(authority)) {
                return findLocalFileByPath(uriPath);
            }
            // RFC 8089 reads the `localhost` host as the local machine, so `file://localhost/dir` names the local `/dir`
            if (authority.equalsIgnoreCase("localhost")) {
                VirtualFile file = findLocalFileByPath(uriPath);
                if (file != null) {
                    return file;
                }
            }
            // Any other host names a UNC path: `file://wsl.localhost/Ubuntu/project` is `//wsl.localhost/Ubuntu/project`.
            // Do not drop the host, because the local path without it is a file that the server did not name.
            return findLocalFileByPath("//" + authority + uriPath);
        }
        catch (URISyntaxException e) {
            LOG.warn("Malformed URI: " + fileUri + "; " + e.getMessage());
            return null;
        }
    }

    /**
     * @see #findFileByUri
     */
    protected @Nullable VirtualFile findLocalFileByPath(String path) {
        return LocalFileSystem.getInstance().findFileByPath(path);
    }

    /**
     * Returns a {@code languageId} field of the <a href="https://microsoft.github.io/language-server-protocol/specification#textDocumentItem">TextDocumentItem</a> class,
     * which is needed for the <a href="https://microsoft.github.io/language-server-protocol/specification#textDocumentItem">textDocument/didOpen</a>
     * notification. {@code languageId} is usually equal to the lowercased file extension. Standard implementation also handles some known
     * exceptions to this rule.
     */
    public String getLanguageId(VirtualFile file) {
        return getDefaultLanguageId(file);
    }

    /**
     * <a href="https://microsoft.github.io/language-server-protocol/specification#initializeParams">InitializeParams</a> object is sent to the LSP
     * server as <a href="https://microsoft.github.io/language-server-protocol/specifications/specification-current/#initialize">initialize</a> request.
     */
    @SuppressWarnings("deprecation")
    public InitializeParams createInitializeParams() {
        InitializeParams params = new InitializeParams();
        Application application = Application.get();
        params.setClientInfo(new ClientInfo(application.getName().get(), application.getBuildNumber().asString()));

        params.setCapabilities(getClientCapabilities());

        if (myRoots.length == 1) {
            // Some old servers might need this old way of setting roots
            params.setRootUri(getFileUri(myRoots[0]));
            params.setRootPath(getFilePath(myRoots[0]));
        }

        List<WorkspaceFolder> workspaceFolders = new ArrayList<>(myRoots.length);
        for (VirtualFile root : myRoots) {
            workspaceFolders.add(new WorkspaceFolder(getFileUri(root), root.getName()));
        }
        params.setWorkspaceFolders(workspaceFolders);

        Object initializationOptions = createInitializationOptions();
        if (initializationOptions != null) {
            params.setInitializationOptions(initializationOptions);
        }
        return params;
    }

    /**
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#clientCapabilities">ClientCapabilities</a> is a mandatory field in
     * <a href="https://microsoft.github.io/language-server-protocol/specification#initializeParams">InitializeParams</a> class, which is sent to the LSP
     * server as <a href="https://microsoft.github.io/language-server-protocol/specifications/specification-current/#initialize">initialize</a> request.
     * <p>
     * Plugins may override this property and tune capabilities according to their needs.
     */
    public ClientCapabilities getClientCapabilities() {
        return LspClientCapabilitiesKt.createClientCapabilities(getLspCustomization());
    }

    /**
     * {@code initializationOptions} is an optional field in the
     * <a href="https://microsoft.github.io/language-server-protocol/specification#initializeParams">InitializeParams</a>
     * class, which is sent to the LSP server as
     * <a href="https://microsoft.github.io/language-server-protocol/specifications/specification-current/#initialize">initialize</a> request.
     */
    public @Nullable Object createInitializationOptions() {
        return null;
    }

    /**
     * Creates an implementation of the {@link org.eclipse.lsp4j.services.LanguageClient} interface.
     * It handles all standard requests and notifications that the LSP server sends to the IDE.
     * 'Standard' requests and notifications are the ones that are documented
     * in the official <a href="https://microsoft.github.io/language-server-protocol/specification">LSP specification</a>.
     * <p>
     * To handle custom undocumented requests/notifications from the server, plugins may override this function
     * and return their subclass of the {@link Lsp4jClient} class.
     * See {@link Lsp4jClient} class documentation for more information.
     */
    public Lsp4jClient createLsp4jClient(LspServerNotificationsHandler handler) {
        return new Lsp4jClient(handler);
    }

    /**
     * Returns a class that should be used as a {@link Lsp4jServer} for this {@link LspClient}.
     * <p>
     * A plugin needs to define a custom sub-interface of the {@link Lsp4jServer} interface only if it needs to send
     * some custom undocumented notifications or requests to the LSP server.
     * <p>
     * Note that there's no need to implement such a custom sub-interface.
     * The {@code lsp4j} library will generate the interface implementation using Java reflection ({@link java.lang.reflect.Proxy Proxy})
     * based on {@code lsp4j}-specific annotations.
     * <p>
     * Example:
     * <pre>
     * public interface FooLsp4jServer extends Lsp4jServer {
     *   &#64;JsonRequest("foo/customRequest")
     *   CompletableFuture&lt;CustomResult&gt; customRequest(CustomParams params);
     *
     *   &#64;JsonNotification("foo/customNotification")
     *   void customNotification(CustomNotificationParams params);
     * }
     * </pre>
     *
     * @see LspClient#sendNotification
     * @see LspClient#sendRequest
     * @see LspClient#sendRequestSync
     */
    public Class<? extends Lsp4jServer> getLsp4jServerClass() {
        return Lsp4jServer.class;
    }

    /**
     * Plugins may provide their listeners to get notified about LSP server events.
     */
    public @Nullable LspServerListener getLspServerListener() {
        return null;
    }

    /**
     * Return {@code true} if the server reports a problem with {@code window/showMessage} and writes the details only to its stderr.
     * Then an error or a warning notification from {@code window/showMessage} or {@code window/showMessageRequest} gets a "Show server log" action.
     * The action selects the console of the server in the Services tool window. The console shows the stderr of the server.
     * Without the Services tool window plugin, the action opens the last part of the stderr in an editor tab.
     */
    public boolean getOfferServerLogInNotifications() {
        return false;
    }

    /**
     * Plugins may override the {@link LspCustomization} class and its properties to customize how different LSP features
     * are handled in the IDE, or disable them completely.
     */
    public LspCustomization getLspCustomization() {
        return myLspCustomization;
    }

    /**
     * Override this function to handle
     * <a href="https://microsoft.github.io/language-server-protocol/specifications/specification-current/#workspace_configuration">workspace/configuration</a>
     * requests from the server. Implementations should check {@code item.getSection()} and respond only to the requests that they understand.
     * As a rule, implementations return {@code super.getWorkspaceConfiguration(item)} as a fallback.
     * <p>
     * Example:
     * <pre>
     *   &#64;Override
     *   public Object getWorkspaceConfiguration(ConfigurationItem item) {
     *     if ("foo".equals(item.getSection())) {
     *       JsonObject result = new JsonObject();
     *       result.addProperty("fooOptionExpectedByServer", true);
     *       return result;
     *     }
     *
     *     return super.getWorkspaceConfiguration(item);
     *   }
     * </pre>
     */
    public @Nullable Object getWorkspaceConfiguration(ConfigurationItem item) {
        return null;
    }

    /**
     * The default {@code languageId} for the file, based on the file name.
     * Not named {@code getLanguageId} because it would clash with the instance function {@link #getLanguageId(VirtualFile)}.
     */
    public static String getDefaultLanguageId(VirtualFile file) {
        String nameLowercased = StringUtil.toLowerCase(file.getName());
        for (Map.Entry<String, String> entry : FILE_NAME_ENDING_TO_LANGUAGE_ID) {
            if (nameLowercased.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        String extension = file.getExtension();
        if (extension == null) {
            return "";
        }
        String extensionLowercased = StringUtil.toLowerCase(extension);
        String languageId = FILE_EXTENSION_TO_LANGUAGE_ID.get(extensionLowercased);
        return languageId != null ? languageId : extensionLowercased;
    }
}

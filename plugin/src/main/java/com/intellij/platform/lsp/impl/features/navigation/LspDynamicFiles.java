// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.navigation;

import com.intellij.platform.lsp.api.LspClientDescriptor;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspCoroutineScopeService;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import consulo.application.Application;
import consulo.document.FileDocumentManager;
import consulo.language.Language;
import consulo.language.file.LanguageFileType;
import consulo.language.file.light.LightVirtualFile;
import consulo.language.plain.PlainTextFileType;
import consulo.util.dataholder.Key;
import consulo.util.io.URLUtil;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.fileType.FileType;
import consulo.virtualFileSystem.fileType.FileTypeRegistry;
import consulo.virtualFileSystem.fileType.UnknownFileType;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentContentParams;
import org.eclipse.lsp4j.TextDocumentContentResult;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The dynamic files that one {@link LspClientImpl} serves: navigation targets whose text comes from the LSP server,
 * for example a class inside a jar or a JDK class from a {@code jrt:} URI.
 * The server provides the text through the {@code workspace/textDocumentContent} request,
 * for the URI schemes it declares in the {@code workspace.textDocumentContent} capability.
 * The text is served as a read-only in-memory file, one instance per URI, so repeated navigation reuses the same editor tab.
 * A content file is reported open ({@code didOpen}) with its URI once; it stays open for the whole session,
 * so the server can analyze requests inside it. A server-requested {@link #refreshContent refresh} syncs the replaced text
 * as a versioned {@code didChange}, so the server never answers requests against stale text.
 * <p>
 * The registry belongs to one {@link LspClientImpl}, so it dies with the server session, and different servers never share an entry.
 * A content file that lost its session, for example after a server restart, is {@link #adopt adopted} by the replacement
 * of the producing client, so an existing editor tab keeps working.
 */
public class LspDynamicFiles {
    public static final Key<String> CONTENT_BY_SERVER_ID = Key.create("lsp.content.by.server.id");

    /**
     * The URI the in-memory content file was produced from, or {@code null} for a regular file.
     */
    public static @Nullable String getDynamicFileUri(VirtualFile file) {
        return file.getUserData(LspClientDescriptor.DYNAMIC_FILE_URI);
    }

    private final LspClientImpl myLspClient;
    private final ConcurrentHashMap<String, VirtualFile> myContentFiles = new ConcurrentHashMap<>();

    public LspDynamicFiles(LspClientImpl lspClient) {
        myLspClient = lspClient;
    }

    /**
     * True when this client served the content of the given {@code file}.
     */
    public boolean contains(VirtualFile file) {
        String uri = getDynamicFileUri(file);
        return uri != null && Objects.equals(myContentFiles.get(uri), file);
    }

    /**
     * The navigation target for {@code targetUri}: an in-memory file with server-provided content when the server declares
     * the URI scheme, otherwise the file {@link LspClientDescriptor#findFileByUri} resolves.
     * Must be called on a background thread.
     */
    public @Nullable VirtualFile findTargetFile(String targetUri) {
        if (LspServerCapabilitiesKt.providesTextDocumentContent(myLspClient, uriScheme(targetUri))) {
            return getOrRequestContent(targetUri);
        }
        return myLspClient.getDescriptor().findFileByUri(targetUri);
    }

    /**
     * Must be called on a background thread.
     */
    public @Nullable VirtualFile getOrRequestContent(String uri) {
        VirtualFile existing = myContentFiles.get(uri);
        if (existing != null) {
            return existing;
        }
        if (!LspServerCapabilitiesKt.providesTextDocumentContent(myLspClient, uriScheme(uri))) {
            return null;
        }

        TextDocumentContentResult result =
            myLspClient.sendRequestSync(it -> it.getWorkspaceService().textDocumentContent(new TextDocumentContentParams(uri)));
        String text = result != null ? result.getText() : null;
        if (text == null) {
            return null;
        }
        // the server can stop while the request runs: a stopped session must not register a file
        if (myLspClient.getState() != LspServerState.Running) {
            return null;
        }

        LightVirtualFile file = createFile(uri, text);
        VirtualFile previous = myContentFiles.putIfAbsent(uri, file);
        if (previous != null) {
            return previous;
        }
        sendDidOpen(uri, file);
        return file;
    }

    /**
     * Handles a server {@code workspace/textDocumentContent/refresh} for {@code uri}: requests the text again and
     * replaces the content of the existing file, so an open editor shows the fresh text.
     * A URI without a content file needs no work, because {@link #getOrRequestContent} fetches the current text.
     */
    public void refreshContent(String uri) {
        if (!(myContentFiles.get(uri) instanceof LightVirtualFile file)) {
            return;
        }
        LspCoroutineScopeService.getInstance(myLspClient.getProject()).launch(() -> {
            TextDocumentContentResult result =
                myLspClient.sendRequestSync(it -> it.getWorkspaceService().textDocumentContent(new TextDocumentContentParams(uri)));
            String text = result != null ? result.getText() : null;
            if (text == null) {
                return;
            }
            Application.get().invokeLater(() -> {
                // the content file is read-only for the user; lift the flag only for the content replacement
                file.setWritable(true);
                try {
                    file.setContent(this, text, false);
                }
                finally {
                    file.setWritable(false);
                }
                // reloads the read-only document from the replaced file content, like an external file change
                FileDocumentManager.getInstance().reloadFiles(file);
                // the server holds the document open with the text of the didOpen: sync the replaced text as a full change
                int version = myLspClient.getDocumentSyncManager().nextDocumentVersion(file);
                VersionedTextDocumentIdentifier identifier = new VersionedTextDocumentIdentifier(uri, version);
                DidChangeTextDocumentParams params = new DidChangeTextDocumentParams(identifier, List.of(new TextDocumentContentChangeEvent(text)));
                myLspClient.sendNotification(it -> it.getTextDocumentService().didChange(params));
            }, myLspClient.getProject().getDisposed());
        });
    }

    /**
     * Adopts a content {@code file} that an earlier session of the same server produced, for example, before a restart.
     * Registers the file and reports it open again, so requests in an existing editor tab keep working.
     * Only the replacement of the producing client adopts: the file carries the producing server identity.
     * Returns {@code false} when another server produced the file, when this server provides no content for the URI scheme,
     * or when this session already serves another instance of {@code uri}.
     */
    public boolean adopt(VirtualFile file, String uri) {
        VirtualFile existing = myContentFiles.get(uri);
        if (existing != null) {
            return existing.equals(file);
        }
        if (!(file instanceof LightVirtualFile lightFile)) {
            return false;
        }
        if (!Objects.equals(file.getUserData(CONTENT_BY_SERVER_ID), LspClientManagerImpl.getServerId(myLspClient))) {
            return false;
        }
        if (!LspServerCapabilitiesKt.providesTextDocumentContent(myLspClient, uriScheme(uri))) {
            return false;
        }
        VirtualFile previous = myContentFiles.putIfAbsent(uri, file);
        if (previous != null) {
            return previous.equals(file);
        }
        sendDidOpen(uri, lightFile);
        return true;
    }

    private void sendDidOpen(String uri, LightVirtualFile file) {
        int version = myLspClient.getDocumentSyncManager().currentDocumentVersion(file);
        TextDocumentItem textDocument = new TextDocumentItem(uri, myLspClient.getDescriptor().getLanguageId(file), version, file.getContent().toString());
        myLspClient.sendNotification(it -> it.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(textDocument)));
    }

    private LightVirtualFile createFile(String uri, String text) {
        String name = URLUtil.unescapePercentSequences(uri.substring(uri.lastIndexOf('/') + 1));
        LightVirtualFile file = new LightVirtualFile(name, fileTypeFor(name, text), text);
        file.setWritable(false);
        file.putUserData(LspClientDescriptor.DYNAMIC_FILE_URI, uri);
        file.putUserData(CONTENT_BY_SERVER_ID, LspClientManagerImpl.getServerId(myLspClient));
        return file;
    }

    /**
     * The {@code workspace/textDocumentContent} result carries no language, so the file type comes from the client side.
     */
    private FileType fileTypeFor(String name, String text) {
        FileType fileType = FileTypeRegistry.getInstance().getFileTypeByFileName(name);
        if (!fileType.isBinary() && !(fileType instanceof UnknownFileType)) {
            return fileType;
        }
        // the descriptor can map a name the platform has no text type for, e.g. `Bar.class`, to the decompiled language
        String languageId = myLspClient.getDescriptor().getLanguageId(new LightVirtualFile(name, PlainTextFileType.INSTANCE, text));
        Language language = null;
        for (Language registered : Language.getRegisteredLanguages()) {
            if (registered.getID().equalsIgnoreCase(languageId)) {
                language = registered;
                break;
            }
        }
        LanguageFileType associatedFileType = language != null ? language.getAssociatedFileType() : null;
        return associatedFileType != null ? associatedFileType : PlainTextFileType.INSTANCE;
    }

    private static @Nullable String uriScheme(String uri) {
        try {
            return new URI(uri).getScheme();
        }
        catch (URISyntaxException e) {
            return null;
        }
    }
}

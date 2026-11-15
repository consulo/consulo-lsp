// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.impl.documentSync.LspDidChangeUtil;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.document.Document;
import consulo.document.event.DocumentEvent;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Default implementation of {@link LspDocumentAdapter} that works with regular text documents.
 * Does not handle notebook cell-relative positions.
 * <p>
 * Not registered as an extension: {@link LspDocumentMapping} falls back to it when no registered adapter accepts a file.
 */
public class DefaultLspDocumentAdapter implements LspDocumentAdapter {
    @Override
    public boolean acceptsUrl(LspClient lspClient, String url) {
        return true;
    }

    @Override
    public boolean acceptsFile(LspClient lspClient, VirtualFile file) {
        return true;
    }

    @Override
    public @Nullable LspDocumentPosition toLspDocumentPosition(LspClient lspClient, VirtualFile file, Document document, int hostOffset) {
        String uri = lspClient.getDescriptor().getFileUri(file);
        return new LspDocumentPosition(
            new DefaultLspDocument(new TextDocumentIdentifier(uri), uri),
            Lsp4jUtilKt.getLsp4jPosition(document, hostOffset)
        );
    }

    @Override
    public List<LspDocumentRange> toLspDocumentRangeSync(LspClient lspClient, VirtualFile file, Document document, Range range) {
        return lspDocumentRange(lspClient, file, range);
    }

    @Override
    public CompletableFuture<List<LspDocumentRange>> toLspDocumentRange(LspClient lspClient, VirtualFile file, Range range) {
        return CompletableFuture.completedFuture(lspDocumentRange(lspClient, file, range));
    }

    private List<LspDocumentRange> lspDocumentRange(LspClient lspClient, VirtualFile file, Range range) {
        String uri = lspClient.getDescriptor().getFileUri(file);
        return List.of(new LspDocumentRange(new DefaultLspDocument(new TextDocumentIdentifier(uri), uri), range));
    }

    @Override
    public List<LspDocument> toLspDocumentsInFileSync(LspClient lspClient, VirtualFile file) {
        return lspDocument(lspClient, file);
    }

    @Override
    public CompletableFuture<List<LspDocument>> toLspDocumentsInFile(LspClient lspClient, VirtualFile file) {
        return CompletableFuture.completedFuture(lspDocument(lspClient, file));
    }

    private List<LspDocument> lspDocument(LspClient lspClient, VirtualFile file) {
        String uri = lspClient.getDescriptor().getFileUri(file);
        return List.of(new DefaultLspDocument(new TextDocumentIdentifier(uri), uri));
    }

    @Override
    public @Nullable LspDocument getLspDocumentByUrl(LspClient lspClient, String targetUri) {
        return lspClient.getDescriptor().findFileByUri(targetUri) != null
            ? new DefaultLspDocument(new TextDocumentIdentifier(targetUri), targetUri)
            : null;
    }

    @Override
    public void sendDidOpen(LspClient lspClient, VirtualFile file, Document document) {
        String languageId = lspClient.getDescriptor().getLanguageId(file);
        String fileUri = lspClient.getDescriptor().getFileUri(file);
        int version = lspClient.getDocumentVersion(document);
        TextDocumentItem item = new TextDocumentItem(fileUri, languageId, version, document.getText());
        lspClient.sendNotification(it -> it.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(item)));
    }

    @Override
    public void sendDidClose(LspClient lspClient, VirtualFile file, Document document) {
        String fileUri = lspClient.getDescriptor().getFileUri(file);
        DidCloseTextDocumentParams params = new DidCloseTextDocumentParams(new TextDocumentIdentifier(fileUri));
        lspClient.sendNotification(it -> it.getTextDocumentService().didClose(params));
    }

    @Override
    public void sendDidChangeFull(LspClient lspClient, VirtualFile file, Document document) {
        DidChangeTextDocumentParams params = LspDidChangeUtil.createFullDidChangeParams(lspClient, document, file);
        lspClient.sendNotification(it -> it.getTextDocumentService().didChange(params));
    }

    @Override
    public void sendDidChangeIncremental(LspClient lspClient, VirtualFile file, DocumentEvent event) {
        DidChangeTextDocumentParams params = LspDidChangeUtil.createIncrementalDidChangeParamsBeforeDocumentChange(lspClient, event, file);
        lspClient.sendNotification(it -> it.getTextDocumentService().didChange(params));
    }

    @Override
    public void sendDidSave(LspClient lspClient, VirtualFile file, Document document, boolean includeText) {
        String fileUri = lspClient.getDescriptor().getFileUri(file);
        TextDocumentIdentifier documentIdentifier = new TextDocumentIdentifier(fileUri);
        String text = includeText ? document.getText() : null;
        DidSaveTextDocumentParams params = new DidSaveTextDocumentParams(documentIdentifier, text);
        lspClient.sendNotification(it -> it.getTextDocumentService().didSave(params));
    }

    private static final class DefaultLspDocument implements LspDocument {
        private final TextDocumentIdentifier myId;
        private final String myFileUri;

        DefaultLspDocument(TextDocumentIdentifier id, String fileUri) {
            myId = id;
            myFileUri = fileUri;
        }

        @Override
        public TextDocumentIdentifier getId() {
            return myId;
        }

        @Override
        public String getFileUri() {
            return myFileUri;
        }

        @Override
        public Range toHostRange(Range range) {
            return range;
        }

        @Override
        public Position toHostPosition(Position position) {
            return position;
        }

        @Override
        public int toHostLine(int line) {
            return line;
        }

        @Override
        public <T> T mapToHost(T input, BiFunction<LspDocument, T, T> transform) {
            return input;
        }

        @Override
        public Document prepareDocument(Document hostDocument, Function<Document, @Nullable Document> build) {
            return hostDocument;
        }
    }
}

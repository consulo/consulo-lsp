// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl;

import com.intellij.platform.lsp.api.LspClient;
import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ExtensionAPI;
import consulo.component.extension.ExtensionPointName;
import consulo.document.Document;
import consulo.document.event.DocumentEvent;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Range;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Extension point for converting between IDE document positions and LSP positions.
 * <p>
 * This interface is responsible for:
 * <ul>
 * <li>Determining which documents this implementation handles</li>
 * <li>Converting between IDE document offsets and LSP document positions</li>
 * <li>Handling document-specific position conversion (e.g., cell URIs and cell-relative positions for notebooks)</li>
 * </ul>
 * <p>
 * Default implementation works with regular text documents.
 * Notebook implementations can provide their own converter by implementing this extension point.
 * <p>
 * Extensions are checked in order of registration. The first extension that returns {@code true} from
 * {@link #acceptsFile} will be used for position conversion.
 *
 * @see LspDocumentPosition
 */
@ExtensionAPI(ComponentScope.APPLICATION)
public interface LspDocumentAdapter {
    ExtensionPointName<LspDocumentAdapter> EP_NAME = ExtensionPointName.create(LspDocumentAdapter.class);

    boolean acceptsUrl(LspClient lspClient, String url);

    boolean acceptsFile(LspClient lspClient, VirtualFile file);

    @Nullable LspDocumentPosition toLspDocumentPosition(LspClient lspClient, VirtualFile file, Document document, int hostOffset);

    List<LspDocumentRange> toLspDocumentRangeSync(LspClient lspClient, VirtualFile file, Document document, Range range);

    CompletableFuture<List<LspDocumentRange>> toLspDocumentRange(LspClient lspClient, VirtualFile file, Range range);

    List<LspDocument> toLspDocumentsInFileSync(LspClient lspClient, VirtualFile file);

    CompletableFuture<List<LspDocument>> toLspDocumentsInFile(LspClient lspClient, VirtualFile file);

    @Nullable LspDocument getLspDocumentByUrl(LspClient lspClient, String targetUri);

    void sendDidOpen(LspClient lspClient, VirtualFile file, Document document);

    void sendDidClose(LspClient lspClient, VirtualFile file, Document document);

    void sendDidChangeFull(LspClient lspClient, VirtualFile file, Document document);

    void sendDidChangeIncremental(LspClient lspClient, VirtualFile file, DocumentEvent event);

    void sendDidSave(LspClient lspClient, VirtualFile file, Document document, boolean includeText);
}

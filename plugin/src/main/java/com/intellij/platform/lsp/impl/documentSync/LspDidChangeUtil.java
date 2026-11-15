package com.intellij.platform.lsp.impl.documentSync;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.document.event.DocumentEvent;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.lang.StringUtil;
import consulo.virtualFileSystem.NonPhysicalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.jspecify.annotations.Nullable;

import java.util.List;

public final class LspDidChangeUtil {
    private LspDidChangeUtil() {
    }

    public static @Nullable VirtualFile getFileToHandle(DocumentEvent event) {
        if (StringUtil.equals(event.getOldFragment(), event.getNewFragment())) {
            return null;
        }
        VirtualFile file = FileDocumentManager.getInstance().getFile(event.getDocument());
        return file != null && !(file.getFileSystem() instanceof NonPhysicalFileSystem) ? file : null;
    }

    /**
     * At the moment of this function call the {@code documentEvent} must be not yet applied to the document,
     * which effectively means that this function must be called from {@link consulo.document.event.DocumentListener#beforeDocumentChange}.
     * This is needed to calculate line numbers in the document as they were *before* the change.
     */
    @RequiredUIAccess
    public static DidChangeTextDocumentParams createIncrementalDidChangeParamsBeforeDocumentChange(
        LspClient lspClient,
        DocumentEvent documentEvent,
        VirtualFile virtualFile
    ) {
        // This function is called at the moment when the `documentEvent` is not yet applied to the document, so
        // the version to declare can't be read off the document -- [LspClient.nextDocumentVersion] advances a
        // version counter this client owns outright instead, so there's nothing to predict.
        VersionedTextDocumentIdentifier versionedIdentifier =
            getVersionedIdentifier(lspClient, virtualFile, lspClient.nextDocumentVersion(documentEvent.getDocument()));

        Range range = Lsp4jUtilKt.getLsp4jRange(documentEvent.getDocument(), documentEvent.getOffset(), documentEvent.getOldLength());
        String text = documentEvent.getNewFragment().toString();
        TextDocumentContentChangeEvent changeEvent = new TextDocumentContentChangeEvent(range, text);

        return new DidChangeTextDocumentParams(versionedIdentifier, List.of(changeEvent));
    }

    @RequiredReadAction
    public static DidChangeTextDocumentParams createFullDidChangeParams(
        LspClient lspClient,
        Document document,
        VirtualFile virtualFile
    ) {
        return new DidChangeTextDocumentParams(
            getVersionedIdentifier(lspClient, virtualFile, lspClient.nextDocumentVersion(document)),
            List.of(new TextDocumentContentChangeEvent(document.getText()))
        );
    }

    private static VersionedTextDocumentIdentifier getVersionedIdentifier(
        LspClient lspClient,
        VirtualFile virtualFile,
        int version
    ) {
        return new VersionedTextDocumentIdentifier(lspClient.getDescriptor().getFileUri(virtualFile), version);
    }
}

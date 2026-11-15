// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspServer;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.access.RequiredWriteAction;
import consulo.application.WriteAction;
import consulo.codeEditor.Editor;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.language.editor.FileModificationService;
import consulo.language.editor.intention.IntentionAction;
import consulo.language.psi.PsiFile;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.module.content.ProjectFileIndex;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.io.PathUtil;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CreateFile;
import org.eclipse.lsp4j.DeleteFile;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.RenameFile;
import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link IntentionAction} that knows how to apply {@link CodeAction} received from the LSP server.
 *
 * <h4>Implementation note</h4>
 * The platform API assumes that the quick fixes for diagnostics are created right at the moment of the code highlighting.
 * For performance reasons, special quick fix stubs are created at that
 * moment. Those stubs do not contain {@link CodeAction} objects. As soon as the platform calls {@link IntentionAction#getText} or
 * {@link IntentionAction#isAvailable} on those stubs, the stubs ask the LSP server to calculate real {@link CodeAction} objects and, once received,
 * create {@link LspIntentionAction} objects. After that, those quick fix stubs delegate {@link #getText}, {@link #isAvailable}, and {@link #invoke} calls to these
 * {@link LspIntentionAction} objects.
 * <p>
 * {@link LspIntentionAction} objects are used not only for quick fixes, but also for other kinds of {@link CodeAction CodeActions}.
 * In platform terms, other kinds of {@code CodeActions} are handled as Intention actions.
 */
public class LspIntentionAction implements IntentionAction {
    private static final Logger LOG = Logger.getInstance(LspIntentionAction.class);

    private final LspClient myLspClient;
    private final CodeAction myInitialCodeAction;

    // 1. If the `initialCodeAction` contains the `edit` property, it means that it is already resolved.
    // 2. If the server doesn't support code actions resolve, it also means that the `initialCodeAction` is already resolved.
    private @Nullable CodeAction myResolvedCodeAction;

    private boolean myUriToDocumentMapInitialized = false;
    private @Nullable Map<String, Document> myUriToDocumentMap = null;

    public LspIntentionAction(LspClient lspClient, CodeAction initialCodeAction) {
        myLspClient = lspClient;
        myInitialCodeAction = initialCodeAction;
        myResolvedCodeAction = initialCodeAction.getEdit() != null || !isResolveProvider(lspClient) ? initialCodeAction : null;
    }

    /**
     * @deprecated Use the LspClient constructor
     */
    @Deprecated
    @SuppressWarnings("deprecation")
    public LspIntentionAction(LspServer lspServer, CodeAction initialCodeAction) {
        this((LspClient) lspServer, initialCodeAction);
    }

    private static boolean isResolveProvider(LspClient lspClient) {
        InitializeResult initializeResult = lspClient.getInitializeResult();
        ServerCapabilities capabilities = initializeResult != null ? initializeResult.getCapabilities() : null;
        Either<Boolean, CodeActionOptions> codeActionProvider = capabilities != null ? capabilities.getCodeActionProvider() : null;
        CodeActionOptions codeActionOptions = codeActionProvider != null ? codeActionProvider.getRight() : null;
        return codeActionOptions != null && Boolean.TRUE.equals(codeActionOptions.getResolveProvider());
    }

    protected LspClient getLspClient() {
        return myLspClient;
    }

    /**
     * @deprecated Use {@link #getLspClient()}
     */
    @Deprecated
    @SuppressWarnings("deprecation")
    protected LspServer getLspServer() {
        return (LspServer) myLspClient;
    }

    private CodeAction getCodeAction() {
        CodeAction resolvedCodeAction = myResolvedCodeAction;
        return resolvedCodeAction != null ? resolvedCodeAction : myInitialCodeAction;
    }

    // `getFamilyName()` is not delegated to this class. The wrapper class returns an empty string. This function is never called.
    // IntentionAction has no getFamilyName(), so this is a plain method, not an override.
    public final String getFamilyName() {
        return "";
    }

    // `startInWriteAction()` is not delegated to this class. The wrapper class returns `false`. This function is never called.
    @Override
    public final boolean startInWriteAction() {
        return false;
    }

    @Override
    public LocalizeValue getText() {
        return LocalizeValue.of(getCodeAction().getTitle());
    }

    @Override
    public boolean isAvailable(Project project, Editor editor, PsiFile psiFile) {
        return isAvailable();
    }

    /**
     * The side effect of this function is that it resolves the {@code initialCodeAction} if needed,
     * and also fills the auxiliary {@code uriToDocumentMap} that is used later in the {@link #invoke} function.
     */
    @RequiredReadAction
    public boolean isAvailable() {
        if (getCodeAction().getDisabled() != null) {
            return false;
        }

        resolveCodeAction();

        WorkspaceEdit workspaceEdit = getCodeAction().getEdit();
        if (workspaceEdit != null && isNotEmpty(workspaceEdit.getChanges()) && isNotEmpty(workspaceEdit.getDocumentChanges())) {
            return false;
        }

        if (!myUriToDocumentMapInitialized) {
            myUriToDocumentMap = workspaceEdit != null ? getUriToDocumentMap(workspaceEdit) : Map.of();
            myUriToDocumentMapInitialized = true;
        }

        return myUriToDocumentMap != null &&
            (workspaceEdit != null || getCodeAction().getCommand() != null);
    }

    /**
     * Must be called in a background thread.
     */
    private void resolveCodeAction() {
        if (myResolvedCodeAction != null) {
            return;
        }

        CodeAction codeAction = getCodeAction();
        CodeAction resolved = myLspClient.sendRequestSync(it -> it.getTextDocumentService().resolveCodeAction(codeAction));
        myResolvedCodeAction = resolved != null ? resolved : myInitialCodeAction;
    }

    @Override
    public void invoke(Project project, Editor editor, PsiFile psiFile) {
        invoke(psiFile.getVirtualFile());
    }

    /**
     * Make sure to call {@link #isAvailable} before calling {@link #invoke},
     * otherwise the auxiliary {@code uriToDocumentMap} will be not ready and the {@code CodeAction} will not be applied.
     */
    @RequiredUIAccess
    public void invoke(@Nullable VirtualFile contextFile) {
        Map<String, Document> uriToDocumentMap = myUriToDocumentMap;
        if (uriToDocumentMap == null) {
            return;
        }
        List<VirtualFile> files = new ArrayList<>(uriToDocumentMap.size());
        for (Document document : uriToDocumentMap.values()) {
            files.add(FileDocumentManager.getInstance().getFile(document));
        }
        if (!FileModificationService.getInstance().prepareVirtualFilesForWrite(myLspClient.getProject(), files)) {
            return;
        }

        WorkspaceEdit workspaceEdit = getCodeAction().getEdit();
        if (workspaceEdit != null && (isNotEmpty(workspaceEdit.getChanges()) || isNotEmpty(workspaceEdit.getDocumentChanges()))) {
            WriteAction.run(() -> applyWorkspaceEdit(workspaceEdit, uriToDocumentMap));
        }

        applyCommand(myLspClient.getDescriptor().getLspCustomization().getCommandsCustomizer(), getCodeAction().getCommand(), contextFile);
    }

    private void applyCommand(LspCommandsCustomizer commandsSupport, @Nullable Command command, @Nullable VirtualFile contextFile) {
        if (commandsSupport instanceof LspCommandsSupport support && command != null && contextFile != null) {
            support.executeCommand(myLspClient, contextFile, command);
        }
    }

    /**
     * @param uriToDocumentMap it's guaranteed that it contains {@code Document} objects for URIs from this {@code workspaceEdit}
     */
    @RequiredWriteAction
    protected void applyWorkspaceEdit(WorkspaceEdit workspaceEdit, Map<String, Document> uriToDocumentMap) {
        Map<String, List<TextEdit>> changes = workspaceEdit.getChanges();
        if (changes != null) {
            for (Map.Entry<String, List<TextEdit>> entry : changes.entrySet()) {
                Document document = Objects.requireNonNull(uriToDocumentMap.get(entry.getKey()));
                if (!Lsp4jUtilKt.applyTextEdits(document, entry.getValue())) {
                    return;
                }
            }
        }

        Map<String, Document> uriToCreatedDocumentMap = new LinkedHashMap<>();

        List<Either<TextDocumentEdit, ResourceOperation>> documentChanges = workspaceEdit.getDocumentChanges();
        if (documentChanges != null) {
            for (Either<TextDocumentEdit, ResourceOperation> editOrResourceOperation : documentChanges) {
                TextDocumentEdit textDocumentEdit = editOrResourceOperation.getLeft();
                if (textDocumentEdit != null) {
                    String uri = textDocumentEdit.getTextDocument().getUri();
                    Document document = uriToDocumentMap.get(uri);
                    if (document == null) {
                        document = uriToCreatedDocumentMap.get(uri);
                    }
                    if (document == null) {
                        LOG.error("No Document for " + uri);
                        return;
                    }
                    if (!Lsp4jUtilKt.applySimpleTextEdits(document, textDocumentEdit.getEdits())) {
                        return;
                    }
                }

                ResourceOperation resourceOperation = editOrResourceOperation.getRight();
                if (resourceOperation != null) {
                    if (resourceOperation instanceof CreateFile createFile) {
                        Document created = createFile(createFile);
                        if (created != null) {
                            uriToCreatedDocumentMap.put(createFile.getUri(), created);
                        }
                    }
                    else if (resourceOperation instanceof DeleteFile deleteFile) {
                        if (deleteFile(deleteFile)) {
                            uriToCreatedDocumentMap.remove(deleteFile.getUri());
                        }
                    }
                    else if (resourceOperation instanceof RenameFile renameFile) {
                        Document renamed = renameFile(renameFile);
                        if (renamed != null) {
                            uriToCreatedDocumentMap.put(renameFile.getNewUri(), renamed);
                        }
                    }
                }
            }
        }
    }

    private @Nullable Document createFile(CreateFile createFile) {
        String fileUri = createFile.getUri();
        VirtualFile parentDir = findOrCreateParentDir(fileUri);
        if (parentDir == null) {
            LOG.warn("Ignoring CreateFile(" + fileUri + "): failed to find or create the parent directory");
            return null;
        }

        VirtualFile createdFile;
        try {
            createdFile = parentDir.createChildData(this, PathUtil.getFileName(fileUri));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Document document = FileDocumentManager.getInstance().getDocument(createdFile);
        if (document == null) {
            LOG.warn("No Document for created file " + createdFile.getPath());
        }
        return document;
    }

    private @Nullable Document renameFile(RenameFile renameFile) {
        String oldUri = renameFile.getOldUri();
        String newUri = renameFile.getNewUri();

        VirtualFile file = myLspClient.getDescriptor().findFileByUri(oldUri);
        if (file == null) {
            LOG.warn("Ignoring RenameFile(" + oldUri + " -> " + newUri + "): file not found");
            return null;
        }

        if (myLspClient.getDescriptor().findFileByUri(newUri) != null) {
            LOG.warn("Ignoring RenameFile(" + oldUri + " -> " + newUri + "): the target file already exists");
            return null;
        }

        VirtualFile parentDir = findOrCreateParentDir(newUri);
        if (parentDir == null) {
            LOG.warn("Ignoring RenameFile(" + oldUri + " -> " + newUri + "): failed to find or create the parent directory");
            return null;
        }

        String newName = PathUtil.getFileName(newUri);
        try {
            if (!parentDir.equals(file.getParent())) {
                file.move(this, parentDir);
            }
            if (!file.getName().equals(newName)) {
                file.rename(this, newName);
            }
        }
        catch (IOException e) {
            LOG.warn("Failed to apply RenameFile(" + oldUri + " -> " + newUri + ")", e);
            return null;
        }

        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            LOG.warn("No Document for renamed file " + file.getPath());
        }
        return document;
    }

    /**
     * @return {@code true} when the file was deleted
     */
    private boolean deleteFile(DeleteFile deleteFile) {
        String fileUri = deleteFile.getUri();

        VirtualFile file = myLspClient.getDescriptor().findFileByUri(fileUri);
        if (file == null) {
            if (deleteFile.getOptions() == null || !Boolean.TRUE.equals(deleteFile.getOptions().getIgnoreIfNotExists())) {
                LOG.warn("Ignoring DeleteFile(" + fileUri + "): file not found");
            }
            return false;
        }

        VirtualFile[] children = file.getChildren();
        if (file.isDirectory() && children != null && children.length > 0 &&
            (deleteFile.getOptions() == null || !Boolean.TRUE.equals(deleteFile.getOptions().getRecursive()))) {
            LOG.warn("Ignoring DeleteFile(" + fileUri + "): the directory is not empty, and the delete is not recursive");
            return false;
        }

        try {
            file.delete(this);
        }
        catch (IOException e) {
            LOG.warn("Failed to apply DeleteFile(" + fileUri + ")", e);
            return false;
        }

        return true;
    }

    private @Nullable VirtualFile findOrCreateParentDir(String fileUri) {
        String existingDirUri = PathUtil.getParentPath(fileUri);
        VirtualFile existingDir = myLspClient.getDescriptor().findFileByUri(existingDirUri);
        while (existingDir == null && !existingDirUri.isEmpty()) {
            existingDirUri = PathUtil.getParentPath(existingDirUri);
            existingDir = myLspClient.getDescriptor().findFileByUri(existingDirUri);
        }

        if (existingDir == null) {
            LOG.warn("Base directory not found for " + fileUri);
            return null;
        }

        String relativeParentPath = PathUtil.getParentPath(fileUri.substring(existingDirUri.length() + 1));
        if (relativeParentPath.isEmpty()) {
            return existingDir;
        }
        try {
            return VirtualFileUtil.createDirectoryIfMissing(existingDir.getPath() + "/" + relativeParentPath);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private @Nullable Map<String, Document> getUriToDocumentMap(WorkspaceEdit edit) {
        Map<String, Document> result = new LinkedHashMap<>();

        Map<String, List<TextEdit>> changes = edit.getChanges();
        if (changes != null) {
            for (String documentUri : changes.keySet()) {
                Document document = getDocument(documentUri);
                if (document == null) {
                    return null;
                }
                result.put(documentUri, document);
            }
        }

        // URIs of the files that will appear only when the resource operations are applied
        Set<String> urisProducedByResourceOps = new HashSet<>();

        List<Either<TextDocumentEdit, ResourceOperation>> documentChanges = edit.getDocumentChanges();
        if (documentChanges != null) {
            for (Either<TextDocumentEdit, ResourceOperation> editOrResourceOperation : documentChanges) {
                TextDocumentEdit textDocumentEdit = editOrResourceOperation.getLeft();
                if (textDocumentEdit != null) {
                    String documentUri = textDocumentEdit.getTextDocument().getUri();
                    if (!urisProducedByResourceOps.contains(documentUri)) {
                        Integer version = textDocumentEdit.getTextDocument().getVersion();
                        Document document = getDocument(documentUri, version != null ? version : -1);
                        if (document == null) {
                            return null;
                        }
                        result.put(documentUri, document);
                    }
                }

                ResourceOperation resourceOperation = editOrResourceOperation.getRight();
                if (resourceOperation != null) {
                    if (resourceOperation instanceof CreateFile createFile) {
                        urisProducedByResourceOps.add(createFile.getUri());
                    }
                    else if (resourceOperation instanceof RenameFile renameFile) {
                        Document document = getDocument(renameFile.getOldUri());
                        if (document == null) {
                            return null;
                        }
                        result.put(renameFile.getOldUri(), document);
                        urisProducedByResourceOps.add(renameFile.getNewUri());
                    }
                }
            }
        }

        return result;
    }

    private @Nullable Document getDocument(String documentUri) {
        return getDocument(documentUri, -1);
    }

    private @Nullable Document getDocument(String documentUri, int version) {
        VirtualFile file = myLspClient.getDescriptor().findFileByUri(documentUri);
        if (file == null) {
            LOG.warn("File not found: " + documentUri);
            return null;
        }

        if (!ProjectFileIndex.getInstance(myLspClient.getProject()).isInContent(file)) {
            LOG.warn("File is not within the project content: " + documentUri);
            return null;
        }

        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            LOG.warn("Document not found for file: " + file);
            return null;
        }

        int documentVersion = myLspClient.getDocumentVersion(document);
        if (version != -1 && documentVersion != version) {
            LOG.info("Ignoring CodeAction for document version " + version + " (" + file.getName() + "); " +
                "current document version: " + documentVersion);
            return null;
        }

        return document;
    }

    // TODO intention preview: apply the edits to a non-physical copy of the file once IntentionAction
    //  supports a preview API.

    private static boolean isNotEmpty(@Nullable Map<?, ?> map) {
        return map != null && !map.isEmpty();
    }

    private static boolean isNotEmpty(@Nullable List<?> list) {
        return list != null && !list.isEmpty();
    }
}

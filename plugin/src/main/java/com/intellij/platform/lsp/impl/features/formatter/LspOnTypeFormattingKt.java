package com.intellij.platform.lsp.impl.features.formatter;

import com.intellij.platform.lsp.api.customization.LspOnTypeFormattingSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspCoroutineScopeService;
import com.intellij.platform.lsp.impl.LspDocumentPosition;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.localize.LspLocalize;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.access.RequiredWriteAction;
import consulo.application.Application;
import consulo.application.ReadAction;
import consulo.codeEditor.Editor;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.language.codeStyle.CodeStyle;
import consulo.language.codeStyle.CommonCodeStyleSettings;
import consulo.language.editor.WriteCommandAction;
import consulo.language.editor.completion.lookup.LookupManager;
import consulo.language.editor.template.TemplateManager;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.TextEdit;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Upstream: the private top-level functions of {@code LspOnTypeFormatting.kt}, shared by {@link LspTypedActionHandler} and
 * {@link LspOnTypeFormattingEnterHandler} (they are in separate files here).
 */
public final class LspOnTypeFormattingKt {
    private LspOnTypeFormattingKt() {
    }

    // Skip formatting when multiple carets, selection exists, and live template or completion is active
    public static boolean shouldPerformOnTypeFormatting(Editor editor) {
        if (editor.getCaretModel().getCaretCount() > 1) {
            return false;
        }
        if (editor.getSelectionModel().hasSelection()) {
            return false;
        }
        return isOnTypeFormattingAllowed(editor);
    }

    @RequiredWriteAction
    public static void performOnTypeFormatting(Editor editor, VirtualFile virtualFile, char charTyped) {
        Project project = editor.getProject();
        if (project == null) {
            return;
        }
        if (virtualFile instanceof VirtualFileWindow) {
            return;
        }
        Document document = FileDocumentManager.getInstance().getCachedDocument(virtualFile);
        if (document == null) {
            return;
        }
        long modificationStamp = document.getModificationStamp();

        LspCoroutineScopeService.getInstance(project).launch(() -> {
            // Complete the LSP on-type formatting request in a read action and apply the received text edits in a write action.
            // The read action is restarted if the document changes between the read and write parts of the coroutine.
            // If the modification stamp changes, or completion/live template becomes active, formatting is skipped.
            // Consulo: a blocking read action; the modification stamp check below covers a change after it.
            ClientAndParams clientAndParams = ReadAction.compute(() -> {
                if (document.getModificationStamp() != modificationStamp || !isOnTypeFormattingAllowed(editor)) {
                    return null;
                }
                LspClientImpl client = null;
                for (LspClientImpl it : LspClientManagerImpl.getInstanceImpl(project).getClientsWithThisFileOpen(virtualFile)) {
                    if (isClientApplicable(it, virtualFile, charTyped)) {
                        client = it;
                        break;
                    }
                }
                if (client == null) {
                    return null;
                }
                DocumentOnTypeFormattingParams params = createParams(client, virtualFile, editor, charTyped);
                if (params == null) {
                    return null;
                }
                return new ClientAndParams(client, params);
            });
            if (clientAndParams == null) {
                return;
            }

            List<? extends TextEdit> textEdits = clientAndParams.myLspClient.sendRequestSync(
                server -> server.getTextDocumentService().onTypeFormatting(clientAndParams.myParams)
            );
            if (textEdits == null) {
                return;
            }

            // upstream: readAndEdtWriteAction { ... writeCommandAction(project, name) { applyTextEdits(document, textEdits) } }
            Application.get().invokeLater(() -> {
                if (project.isDisposed()) {
                    return;
                }
                if (document.getModificationStamp() != modificationStamp || !isOnTypeFormattingAllowed(editor)) {
                    return;
                }
                WriteCommandAction.writeCommandAction(project)
                    .withName(LspLocalize.commandNameLspOnTypeFormatting().get())
                    .run(() -> Lsp4jUtilKt.applyTextEdits(document, new ArrayList<>(textEdits)));
            });
        });
    }

    private static final class ClientAndParams {
        private final LspClientImpl myLspClient;
        private final DocumentOnTypeFormattingParams myParams;

        private ClientAndParams(LspClientImpl lspClient, DocumentOnTypeFormattingParams params) {
            myLspClient = lspClient;
            myParams = params;
        }
    }

    // check that a live template or completion is not active
    private static boolean isOnTypeFormattingAllowed(Editor editor) {
        Project project = editor.getProject();
        if (project == null) {
            return false;
        }
        if (project.isDefault()) {
            return false;
        }
        if (LookupManager.getActiveLookup(editor) != null) {
            return false;
        }
        return TemplateManager.getInstance(project).getActiveTemplate(editor) == null;
    }

    private static boolean isClientApplicable(LspClientImpl client, VirtualFile virtualFile, char c) {
        if (!(client.getDescriptor().getLspCustomization().getOnTypeFormattingCustomizer() instanceof LspOnTypeFormattingSupport)) {
            return false;
        }
        List<String> triggerCharacters = LspServerCapabilitiesKt.getOnTypeFormattingTriggerCharacters(client, virtualFile);
        if (triggerCharacters == null) {
            return false;
        }
        return triggerCharacters.contains(String.valueOf(c));
    }

    @RequiredReadAction
    private static @Nullable DocumentOnTypeFormattingParams createParams(LspClientImpl client, VirtualFile virtualFile, Editor editor, char c) {
        Project project = editor.getProject();
        if (project == null) {
            return null;
        }
        int offset = editor.getCaretModel().getOffset();

        LspDocumentPosition docPosition = client.getDocumentMapping().getDocumentPosition(virtualFile, editor.getDocument(), offset);
        if (docPosition == null) {
            return null;
        }

        // upstream: CodeStyle.getIndentOptions(project, virtualFile)
        CommonCodeStyleSettings.IndentOptions indentOptions = CodeStyle.getIndentOptions(project, editor.getDocument());
        FormattingOptions formattingOptions = new FormattingOptions();
        formattingOptions.setTabSize(indentOptions.TAB_SIZE);
        formattingOptions.setInsertSpaces(!indentOptions.USE_TAB_CHARACTER);

        return new DocumentOnTypeFormattingParams(
            docPosition.getDocument().getId(),
            formattingOptions,
            docPosition.getPosition(),
            String.valueOf(c)
        );
    }
}

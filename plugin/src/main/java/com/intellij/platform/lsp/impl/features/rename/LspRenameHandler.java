package com.intellij.platform.lsp.impl.features.rename;

import com.intellij.platform.lsp.api.customization.LspRenameCustomizer;
import com.intellij.platform.lsp.api.customization.LspRenameSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspDocumentPosition;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.impl.util.LspWorkspaceEditApplier;
import com.intellij.platform.lsp.localize.LspLocalize;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.ReadAction;
import consulo.application.WriteAction;
import consulo.application.dumb.DumbAware;
import consulo.codeEditor.Editor;
import consulo.component.ProcessCanceledException;
import consulo.dataContext.DataContext;
import consulo.document.Document;
import consulo.document.util.DocumentUtil;
import consulo.document.util.TextRange;
import consulo.language.editor.WriteCommandAction;
import consulo.language.editor.hint.HintManager;
import consulo.language.editor.refactoring.TitledHandler;
import consulo.language.editor.refactoring.localize.RefactoringLocalize;
import consulo.language.editor.refactoring.rename.RenameHandler;
import consulo.language.editor.template.Template;
import consulo.language.editor.template.TemplateManager;
import consulo.language.editor.template.TemplateState;
import consulo.language.editor.template.TextResult;
import consulo.language.editor.template.event.TemplateEditingAdapter;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.localize.LocalizeValue;
import consulo.lsp.impl.compat.ConstantNode;
import consulo.lsp.impl.compat.TasksKt;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.undoRedo.CommandProcessor;
import consulo.undoRedo.FinishMarkAction;
import consulo.undoRedo.StartMarkAction;
import consulo.util.lang.Pair;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Upstream: {@code <renameHandler order="last"/>}. Consulo reserves {@code first}/{@code last} for the plugin that owns the extension
 * point; the order of the rename handlers does not matter in Consulo ({@code RenameHandlerRegistry} offers a chooser sorted by title
 * when several handlers are available), so it is dropped.
 */
@ExtensionImpl
public class LspRenameHandler implements RenameHandler, TitledHandler, DumbAware {
    private static final String PRIMARY_VARIABLE_NAME = "LSP_RENAME_PRIMARY";

    @Override
    public LocalizeValue getActionTitleValue() {
        return LspLocalize.lspRenameActionText();
    }

    @Override
    @RequiredReadAction
    public boolean isAvailableOnDataContext(DataContext dataContext) {
        Editor editor = dataContext.getData(Editor.KEY);
        if (editor == null) {
            return false;
        }
        Project project = editor.getProject();
        if (project == null) {
            return false;
        }
        VirtualFile file = dataContext.getData(VirtualFile.KEY);
        if (file == null) {
            return false;
        }

        return findLspClientForRename(project, file) != null;
    }

    /**
     * Consulo: {@link RenameHandler#isRenaming} has no default implementation; IntelliJ's default delegates to
     * {@link #isAvailableOnDataContext}.
     */
    @Override
    @RequiredReadAction
    public boolean isRenaming(DataContext dataContext) {
        return isAvailableOnDataContext(dataContext);
    }

    @Override
    @RequiredUIAccess
    public void invoke(Project project, @Nullable Editor editor, @Nullable PsiFile file, @Nullable DataContext dataContext) {
        if (editor == null || file == null || dataContext == null) {
            return;
        }

        VirtualFile virtualFile = file.getVirtualFile();
        if (virtualFile == null) {
            return;
        }
        LspClientImpl lspClient = findLspClientForRename(project, virtualFile);
        if (lspClient == null) {
            return;
        }

        LspDocumentPosition docPosition =
            lspClient.getDocumentMapping().getDocumentPosition(virtualFile, editor.getDocument(), editor.getCaretModel().getOffset());
        if (docPosition == null) {
            return;
        }
        TextDocumentIdentifier textDocumentIdentifier = docPosition.getDocument().getId();
        org.eclipse.lsp4j.Position position = docPosition.getPosition();
        PrepareRenameParams params = new PrepareRenameParams(textDocumentIdentifier, position);

        Pair<TextRange, @Nullable String> prepareRenameResult = prepareRename(lspClient, editor, params, virtualFile);

        if (prepareRenameResult == null) {
            return;
        }

        TextRange range = prepareRenameResult.getFirst();
        String placeholder = prepareRenameResult.getSecond();

        String initialName = placeholder != null ? placeholder : editor.getDocument().getText(range);

        startInlineRename(project, editor, range, initialName, newName -> {
            RenameParams renameParams = new RenameParams(textDocumentIdentifier, position, newName);
            performRename(editor, lspClient, renameParams);
        });
    }

    @Override
    @RequiredUIAccess
    public void invoke(Project project, PsiElement[] elements, @Nullable DataContext dataContext) {
        // Not used for LSP-based rename
    }

    @RequiredReadAction
    private static @Nullable LspClientImpl findLspClientForRename(Project project, VirtualFile file) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile == null) {
            return null;
        }

        for (LspClientImpl lspClient : LspClientManagerImpl.getInstanceImpl(project).getClientsWithThisFileOpen(file)) {
            LspRenameCustomizer customizer = lspClient.getDescriptor().getLspCustomization().getRenameCustomizer();
            if (customizer instanceof LspRenameSupport renameSupport
                && LspServerCapabilitiesKt.supportsRename(lspClient, file)
                && renameSupport.shouldRunRename(psiFile)) {
                return lspClient;
            }
        }
        return null;
    }

    @RequiredUIAccess
    private static @Nullable Pair<TextRange, @Nullable String> prepareRename(
        LspClientImpl lspClient,
        Editor editor,
        PrepareRenameParams params,
        VirtualFile virtualFile
    ) {
        LspRenameSupport renameCustomizer = (LspRenameSupport) lspClient.getDescriptor().getLspCustomization().getRenameCustomizer();
        Document document = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();

        // If server doesn't support prepareRename, fall back to word-at-range
        if (!LspServerCapabilitiesKt.supportsPrepareRename(lspClient, virtualFile)) {
            TextRange wordRange = renameCustomizer.getRenameableRangeAtOffset(document, offset);
            return wordRange != null ? Pair.create(wordRange, null) : null;
        }

        Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior> result;
        try {
            result = TasksKt.runWithModalProgressBlocking(
                lspClient.getProject(),
                LspLocalize.lspRenamePrepareProgressTitle(),
                () -> TasksKt.await(lspClient.sendRequest(server -> server.getTextDocumentService().prepareRename(params)))
            );
        }
        catch (ResponseErrorException e) {
            if (e.getResponseError() != null && e.getResponseError().getMessage() != null) {
                showErrorHint(editor, e.getResponseError().getMessage());
            }
            result = null;
        }
        catch (ProcessCanceledException e) {
            // Consulo: the user canceled the modal progress
            return null;
        }

        if (result == null) {
            return null;
        }
        if (result.isFirst()) {
            TextRange range = Lsp4jUtilKt.getRangeInDocument(document, result.getFirst());
            return range != null ? Pair.create(range, null) : null;
        }
        if (result.isSecond()) {
            PrepareRenameResult prepareRenameResult = result.getSecond();
            TextRange range = Lsp4jUtilKt.getRangeInDocument(document, prepareRenameResult.getRange());
            return range != null ? Pair.create(range, prepareRenameResult.getPlaceholder()) : null;
        }
        TextRange wordRange = renameCustomizer.getRenameableRangeAtOffset(document, offset);
        return wordRange != null ? Pair.create(wordRange, null) : null;
    }

    @RequiredUIAccess
    private static void performRename(Editor editor, LspClientImpl lspClient, RenameParams params) {
        try {
            WorkspaceEdit workspaceEdit = TasksKt.runWithModalProgressBlocking(
                lspClient.getProject(),
                LspLocalize.lspRenameProgressTitle(),
                () -> TasksKt.await(lspClient.sendRequest(server -> server.getTextDocumentService().rename(params)))
            );
            if (workspaceEdit == null) {
                return;
            }

            // upstream: readAndEdtWriteAction { ... writeAction { applier.applyWorkspaceEdit() } }; this is already the UI thread
            LspWorkspaceEditApplier applier = ReadAction.compute(() -> LspWorkspaceEditApplier.create(lspClient, workspaceEdit));
            if (applier == null) {
                return;
            }
            applier.applyWorkspaceEdit();
        }
        catch (ResponseErrorException e) {
            if (e.getResponseError() != null && e.getResponseError().getMessage() != null) {
                showErrorHint(editor, e.getResponseError().getMessage());
            }
        }
        catch (ProcessCanceledException e) {
            // Consulo: the user canceled the modal progress
        }
    }

    private static void showErrorHint(Editor editor, String errorMessage) {
        HintManager.getInstance().showErrorHint(
            editor, errorMessage,
            editor.getCaretModel().getOffset(), editor.getCaretModel().getOffset(),
            HintManager.ABOVE,
            HintManager.HIDE_BY_ANY_KEY | HintManager.HIDE_BY_TEXT_CHANGE | HintManager.UPDATE_BY_SCROLLING,
            3000
        );
    }

    @RequiredUIAccess
    private static void startInlineRename(
        Project project,
        Editor editor,
        TextRange textRange,
        String initialName,
        Consumer<String> onRename
    ) {
        Document document = editor.getDocument();
        String commandName = RefactoringLocalize.renamingCommandName(initialName).get();

        // Start a mark action to group all rename operations into a single undo unit
        // StartMarkAction.start must be called inside a command
        StartMarkAction[] startMarkAction = new StartMarkAction[1];
        boolean[] alreadyStarted = new boolean[1];
        CommandProcessor.getInstance().executeCommand(project, () -> {
            try {
                startMarkAction[0] = StartMarkAction.start(document, project, commandName);
            }
            catch (StartMarkAction.AlreadyStartedException e) {
                alreadyStarted[0] = true;
            }
        }, commandName, null);
        if (alreadyStarted[0] || startMarkAction[0] == null) {
            return;
        }

        Runnable finishMarkAction = () -> FinishMarkAction.finish(project, document, startMarkAction[0]);

        Template template = TemplateManager.getInstance(project).createTemplate("", "");

        // text before renaming range + variable + text after renaming range
        String documentText = document.getText();
        template.addTextSegment(documentText.substring(0, textRange.getStartOffset()));
        template.addVariableSegment(PRIMARY_VARIABLE_NAME);
        template.addTextSegment(documentText.substring(textRange.getEndOffset()));

        ConstantNode expression = new ConstantNode(initialName);
        template.addVariable(PRIMARY_VARIABLE_NAME, expression, expression, true, false);

        WriteCommandAction.writeCommandAction(project).withName(commandName).run(() -> {
            // Configure template for inline editing
            template.setInline(true);
            template.setToIndent(false);
            // Consulo: no `Template.isToShortenLongNames`; a raw template does not shorten names without a TemplateOptionalProcessor
            template.setToReformat(false);

            editor.getCaretModel().moveToOffset(0);

            // Delete the text range content (template will insert its content)
            DocumentUtil.executeInBulk(document, true, () -> document.deleteString(textRange.getStartOffset(), textRange.getEndOffset()));

            Runnable restoreDocument = () -> WriteAction.run(() -> document.replaceString(
                0,
                document.getTextLength(),
                documentText.substring(0, textRange.getStartOffset()) + initialName + documentText.substring(textRange.getEndOffset())
            ));

            // upstream: TemplateManager.runTemplate(editor, template) returns the TemplateState
            TemplateManager templateManager = TemplateManager.getInstance(project);
            templateManager.startTemplate(editor, template);
            TemplateState templateState = templateManager.getTemplateState(editor);
            if (templateState == null) {
                restoreDocument.run();
                finishMarkAction.run();
                return;
            }

            // upstream: TemplateResultListener (Canceled / BrokenOff / Finished) on top of TemplateEditingAdapter
            templateState.addTemplateStateListener(new TemplateEditingAdapter() {
                @Override
                public void templateCancelled(Template t) {
                    // undo -> cancelled
                    onResult(null);
                }

                @Override
                public void templateFinished(Template t, boolean brokenOff) {
                    onResult(brokenOff);
                }

                /**
                 * @param brokenOff {@code null} for {@code Canceled}, {@code true} for {@code BrokenOff}, {@code false} for {@code Finished}
                 */
                private void onResult(@Nullable Boolean brokenOff) {
                    try {
                        if (brokenOff == null) {
                            // undo -> cancelled
                            return;
                        }
                        if (brokenOff) {
                            // esc -> brokenOff
                            restoreDocument.run();
                        }
                        else {
                            TextResult value = templateState.getVariableValue(PRIMARY_VARIABLE_NAME);
                            String newName = value != null ? value.getText() : null;
                            restoreDocument.run();
                            if (newName != null && !newName.isBlank() && !newName.equals(initialName)) {
                                onRename.accept(newName);
                            }
                        }
                    }
                    finally {
                        // Changes the default command name to ensure the undo action displays proper command name instead of "Go to Next Code Template Tab"
                        if (CommandProcessor.getInstance().getCurrentCommandName() != null) {
                            CommandProcessor.getInstance().setCurrentCommandName(commandName);
                        }
                        finishMarkAction.run();
                    }
                }
            });
        });
    }
}

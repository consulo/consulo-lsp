package com.intellij.platform.lsp.impl.features.codeActions;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.customization.LspCodeActionsCustomizer;
import com.intellij.platform.lsp.api.customization.LspCodeActionsSupport;
import com.intellij.platform.lsp.api.customization.LspIntentionAction;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspDocumentRange;
import com.intellij.platform.lsp.impl.features.intention.LspIntentionActionService;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.application.ReadAction;
import consulo.codeEditor.Caret;
import consulo.codeEditor.Editor;
import consulo.codeEditor.EditorPopupHelper;
import consulo.component.ProcessCanceledException;
import consulo.document.Document;
import consulo.language.editor.hint.HintManager;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.localize.LocalizeValue;
import consulo.lsp.impl.compat.TasksKt;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.SimpleListCellRenderer;
import consulo.ui.ex.popup.JBPopup;
import consulo.ui.ex.popup.JBPopupFactory;
import consulo.undoRedo.CommandProcessor;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeActionTriggerKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper functions for the LSP refactoring code actions.
 */
public final class LspRefactoringCodeActionsKt {
    private LspRefactoringCodeActionsKt() {
    }

    private static final class ClientCodeAction {
        private final LspClientImpl myLspClient;
        private final LspIntentionAction myIntentionAction;

        private ClientCodeAction(LspClientImpl lspClient, LspIntentionAction intentionAction) {
            myLspClient = lspClient;
            myIntentionAction = intentionAction;
        }

        public LspClientImpl getLspClient() {
            return myLspClient;
        }

        public LspIntentionAction getIntentionAction() {
            return myIntentionAction;
        }
    }

    /**
     * Same as {@link #requestAndApplyCodeActions(List, Editor, VirtualFile, List, LocalizeValue, LocalizeValue, LocalizeValue, int, int)}
     * for the caret or selection of the primary caret.
     */
    @RequiredUIAccess
    public static void requestAndApplyCodeActions(
        List<LspClientImpl> lspClients,
        Editor editor,
        VirtualFile virtualFile,
        List<String> kinds,
        LocalizeValue progressTitle,
        LocalizeValue noActionsMessage,
        LocalizeValue popupTitle
    ) {
        Caret primaryCaret = editor.getCaretModel().getPrimaryCaret();
        int offset = primaryCaret.getSelectionStart();
        requestAndApplyCodeActions(lspClients, editor, virtualFile, kinds, progressTitle, noActionsMessage, popupTitle, offset);
    }

    @RequiredUIAccess
    public static void requestAndApplyCodeActions(
        List<LspClientImpl> lspClients,
        Editor editor,
        VirtualFile virtualFile,
        List<String> kinds,
        LocalizeValue progressTitle,
        LocalizeValue noActionsMessage,
        LocalizeValue popupTitle,
        int offset
    ) {
        int length = editor.getCaretModel().getPrimaryCaret().getSelectionEnd() - offset;
        requestAndApplyCodeActions(lspClients, editor, virtualFile, kinds, progressTitle, noActionsMessage, popupTitle, offset, length);
    }

    /**
     * Requests the code actions of the given {@code kinds} from every client of {@code lspClients} for {@code offset} and {@code length},
     * which default to the caret or selection.
     * The {@code kinds} list is a preference order: the actions come from the first kind that yields any, and the later kinds are not requested.
     * A disabled action is not a result: it never stops that fallback, and its reason is the hint when no kind yields an enabled action.
     * The actions go through {@link LspCodeActionsSupport#createIntentionAction}, so a plugin filters the refactoring shortcuts like the intention popup.
     * A deferred caller passes the position it captured at the invocation, so a later caret move cannot retarget the request.
     * Then it shows an error hint for zero results, applies a single result, or opens a chooser popup.
     * A client that fails the request contributes no actions; its error shows only when every client returns nothing.
     */
    @RequiredUIAccess
    public static void requestAndApplyCodeActions(
        List<LspClientImpl> lspClients,
        Editor editor,
        VirtualFile virtualFile,
        List<String> kinds,
        LocalizeValue progressTitle,
        LocalizeValue noActionsMessage,
        LocalizeValue popupTitle,
        int offset,
        int length
    ) {
        if (lspClients.isEmpty()) {
            return;
        }
        Project project = lspClients.get(0).getProject();
        Document document = editor.getDocument();

        ResponseErrorException[] firstError = new ResponseErrorException[1];
        String[] firstDisabledReason = new String[1];
        List<ClientCodeAction> codeActions;
        try {
            codeActions = TasksKt.runWithModalProgressBlocking(project, progressTitle, () -> {
                for (String kind : kinds) {
                    List<ClientCodeAction> kindActions = new ArrayList<>();
                    for (LspClientImpl lspClient : lspClients) {
                        LspCodeActionsCustomizer customizer = lspClient.getDescriptor().getLspCustomization().getCodeActionsCustomizer();
                        if (!(customizer instanceof LspCodeActionsSupport codeActionsSupport)) {
                            continue;
                        }
                        List<CodeAction> received;
                        try {
                            received = requestCodeActions(lspClient, document, virtualFile, offset, length, kind);
                        }
                        catch (ResponseErrorException e) {
                            if (firstError[0] == null) {
                                firstError[0] = e;
                            }
                            received = List.of();
                        }
                        List<CodeAction> enabled = new ArrayList<>();
                        CodeAction firstDisabled = null;
                        for (CodeAction codeAction : received) {
                            if (codeAction.getDisabled() != null) {
                                if (firstDisabled == null) {
                                    firstDisabled = codeAction;
                                }
                            }
                            else {
                                enabled.add(codeAction);
                            }
                        }
                        if (firstDisabledReason[0] == null && firstDisabled != null) {
                            String reason = firstDisabled.getDisabled().getReason();
                            if (reason != null && !reason.isBlank()) {
                                firstDisabledReason[0] = reason;
                            }
                        }
                        for (CodeAction codeAction : enabled) {
                            LspIntentionAction intentionAction = codeActionsSupport.createIntentionAction((LspClient) lspClient, codeAction);
                            if (intentionAction != null) {
                                kindActions.add(new ClientCodeAction(lspClient, intentionAction));
                            }
                        }
                    }
                    if (!kindActions.isEmpty()) {
                        return kindActions;
                    }
                }
                return List.of();
            });
        }
        catch (ProcessCanceledException e) {
            // the user canceled the modal progress
            return;
        }

        if (codeActions.isEmpty()) {
            String message = firstDisabledReason[0];
            if (message == null && firstError[0] != null && firstError[0].getResponseError() != null) {
                message = firstError[0].getResponseError().getMessage();
            }
            showErrorHint(editor, message != null ? LocalizeValue.of(message) : noActionsMessage);
        }
        else if (codeActions.size() == 1) {
            applyCodeAction(codeActions.get(0), editor, virtualFile, progressTitle, noActionsMessage);
        }
        else {
            JBPopup popup = JBPopupFactory.getInstance()
                .createPopupChooserBuilder(codeActions)
                .setTitle(popupTitle.get())
                .setRenderer(SimpleListCellRenderer.<ClientCodeAction>create("", it -> it.getIntentionAction().getText().get()))
                .setItemChosenCallback(it -> applyCodeAction(it, editor, virtualFile, progressTitle, noActionsMessage))
                .createPopup();
            EditorPopupHelper.getInstance().showPopupInBestPositionFor(editor, popup);
        }
    }

    /**
     * Requests the code actions; called in a background thread under the modal progress.
     *
     * @throws ResponseErrorException if the server responds with an error
     */
    private static List<CodeAction> requestCodeActions(
        LspClientImpl lspClient,
        Document document,
        VirtualFile virtualFile,
        int offset,
        int length,
        String kind
    ) {
        List<LspDocumentRange> lspDocumentRanges = ReadAction.compute(() -> {
            Range hostRange = Lsp4jUtilKt.getLsp4jRange(document, offset, length);
            return lspClient.getDocumentMapping().getDocumentRangesSync(virtualFile, document, hostRange);
        });
        CodeActionContext codeActionContext = new CodeActionContext();
        codeActionContext.setDiagnostics(List.of());
        codeActionContext.setOnly(List.of(kind));
        codeActionContext.setTriggerKind(CodeActionTriggerKind.Invoked);

        List<Either<Command, CodeAction>> lsp4jResults = new ArrayList<>();
        for (LspDocumentRange lspDocumentRange : lspDocumentRanges) {
            CodeActionParams params = new CodeActionParams(lspDocumentRange.getDocument().getId(), lspDocumentRange.getRange(), codeActionContext);
            List<Either<Command, CodeAction>> result =
                TasksKt.await(lspClient.sendRequest(server -> server.getTextDocumentService().codeAction(params)));
            if (result != null) {
                lsp4jResults.addAll(result);
            }
        }
        // A bare `Command` and a kind-less `CodeAction` carry no kind to check; trust the request's `only` filter for them.
        // A kind counts only inside the requested dot-separated subtree: `refactor.inline2` does not answer `refactor.inline`.
        List<CodeAction> codeActions = new ArrayList<>();
        for (Either<Command, CodeAction> either : lsp4jResults) {
            if (either.isLeft() || isOfKind(either.getRight(), kind)) {
                codeActions.add(LspIntentionActionService.toCodeAction(either));
            }
        }
        return codeActions;
    }

    private static boolean isOfKind(@Nullable CodeAction codeAction, String kind) {
        String k = codeAction != null ? codeAction.getKind() : null;
        return k == null || k.equals(kind) || k.startsWith(kind + ".");
    }

    @RequiredUIAccess
    private static void applyCodeAction(
        ClientCodeAction clientCodeAction,
        Editor editor,
        VirtualFile virtualFile,
        LocalizeValue progressTitle,
        LocalizeValue noActionsMessage
    ) {
        LspClientImpl lspClient = clientCodeAction.getLspClient();
        LspIntentionAction intentionAction = clientCodeAction.getIntentionAction();
        Project project = lspClient.getProject();
        // the IntentionAction overloads, like the intention wrapper calls them, so a subclass from `createIntentionAction` keeps its overrides;
        // the PSI file is looked up on each side, so none crosses the thread boundary
        boolean available;
        try {
            available = TasksKt.runWithModalProgressBlocking(project, progressTitle, () -> ReadAction.compute(() -> {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
                return psiFile != null && intentionAction.isAvailable(project, editor, psiFile);
            }));
        }
        catch (ProcessCanceledException e) {
            // the user canceled the modal progress
            return;
        }
        PsiFile psiFile = available ? PsiManager.getInstance(project).findFile(virtualFile) : null;
        if (psiFile == null) {
            showErrorHint(editor, noActionsMessage);
            return;
        }
        // a refactoring action handler, unlike an intention, gets no surrounding command from the platform
        LocalizeValue commandName = intentionAction.getText();
        CommandProcessor.getInstance().newCommand()
            .project(project)
            .name(commandName)
            .run(() -> intentionAction.invoke(project, editor, psiFile));
    }

    public static void showErrorHint(Editor editor, LocalizeValue errorMessage) {
        HintManager.getInstance().showErrorHint(
            editor, errorMessage,
            editor.getCaretModel().getOffset(), editor.getCaretModel().getOffset(),
            HintManager.ABOVE,
            HintManager.HIDE_BY_ANY_KEY | HintManager.HIDE_BY_TEXT_CHANGE | HintManager.UPDATE_BY_SCROLLING,
            3000
        );
    }
}

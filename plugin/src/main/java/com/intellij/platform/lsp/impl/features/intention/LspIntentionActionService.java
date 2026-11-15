package com.intellij.platform.lsp.impl.features.intention;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.customization.LspCodeActionsSupport;
import com.intellij.platform.lsp.api.customization.LspIntentionAction;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspDocumentRange;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.impl.cache.LspPerFileCache;
import com.intellij.platform.lsp.impl.features.quickFix.LspIntentionActionWrapperBase;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.Application;
import consulo.application.progress.ProgressManager;
import consulo.codeEditor.Caret;
import consulo.codeEditor.Editor;
import consulo.disposer.Disposable;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.language.editor.inject.EditorWindow;
import consulo.language.editor.intention.IntentionAction;
import consulo.language.editor.intention.IntentionActionDelegate;
import consulo.language.editor.intention.IntentionManager;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.lang.Pair;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeActionTriggerKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.ArrayList;
import java.util.List;

@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
@Singleton
public class LspIntentionActionService implements Disposable {
    private static final Logger LOG = Logger.getInstance(LspIntentionActionService.class);

    public static LspIntentionActionService getInstance(Project project) {
        return project.getInstance(LspIntentionActionService.class);
    }

    private final Project myProject;
    private final LspPerFileCache<Pair<Integer, Integer>, List<LspIntentionAction>> myIntentionActionsCache;

    @Inject
    public LspIntentionActionService(Project project) {
        myProject = project;
        myIntentionActionsCache = new LspPerFileCache<>(project);
    }

    public List<LspIntentionAction> getIntentionActions(Editor editor) {
        if (editor instanceof EditorWindow) {
            LOG.error("EditorWindow not expected here");
            return List.of();
        }

        Document document = editor.getDocument();
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null) {
            return List.of();
        }
        Caret currentCaret = editor.getCaretModel().getPrimaryCaret();
        int offset = currentCaret.getSelectionStart();
        int length = currentCaret.getSelectionEnd() - offset;

        if (Application.get().isDispatchThread()) {
            List<LspIntentionAction> computed = myIntentionActionsCache.getIfComputed(file, Pair.create(offset, length));
            if (computed != null) {
                return computed;
            }
            // Consulo: Alt+Enter evaluates intentions on the UI thread (see LspIntentionActionWrapperBase)
            if (!LspIntentionActionWrapperBase.canComputeUnderModalProgress()) {
                return List.of();
            }
            List<LspIntentionAction> result = LspIntentionActionWrapperBase.computeUnderModalProgress(myProject, () -> {
                List<LspIntentionAction> actions = myIntentionActionsCache.getOrCompute(
                    file,
                    Pair.create(offset, length),
                    () -> computeIntentionActions(file, document, offset, length)
                );
                if (actions == null) {
                    return List.of();
                }
                // `isAvailable()` resolves the code action (`codeAction/resolve`), which upstream does in a background thread too
                for (LspIntentionAction action : actions) {
                    ProgressManager.checkCanceled();
                    action.isAvailable();
                }
                return actions;
            });
            return result != null ? result : List.of();
        }

        List<LspIntentionAction> result = myIntentionActionsCache.getOrCompute(
            file,
            Pair.create(offset, length),
            () -> computeIntentionActions(file, document, offset, length)
        );
        return result != null ? result : List.of();
    }

    private List<LspIntentionAction> computeIntentionActions(
        VirtualFile file,
        Document document,
        int offset,
        int length
    ) {
        List<LspIntentionAction> result = new ArrayList<>();

        for (LspClientImpl lspClient : LspClientManagerImpl.getInstanceImpl(myProject).getClientsWithThisFileOpen(file)) {
            ProgressManager.checkCanceled();
            if (!(lspClient.getDescriptor().getLspCustomization().getCodeActionsCustomizer() instanceof LspCodeActionsSupport codeActionsSupport) ||
                !codeActionsSupport.getIntentionActionsSupport()) {
                continue;
            }
            // When asking for Intentions, we are not interested in servers that support only quick fixes
            boolean somethingButQuickFix = LspServerCapabilitiesKt.supportsCodeActions(
                lspClient,
                kinds -> kinds.stream().anyMatch(kind -> !kind.startsWith(CodeActionKind.QuickFix))
            );
            if (!somethingButQuickFix) {
                continue;
            }

            Range hostRange = Lsp4jUtilKt.getLsp4jRange(document, offset, length);
            CodeActionContext codeActionContext = new CodeActionContext();
            codeActionContext.setDiagnostics(List.of());
            codeActionContext.setTriggerKind(CodeActionTriggerKind.Automatic);

            List<LspDocumentRange> lspDocuments = lspClient.getDocumentMapping().getDocumentRangesSync(file, document, hostRange);
            List<Either<Command, CodeAction>> lsp4jResults = new ArrayList<>();
            for (LspDocumentRange lspDocumentRange : lspDocuments) {
                CodeActionParams params = new CodeActionParams(lspDocumentRange.getDocument().getId(), lspDocumentRange.getRange(), codeActionContext);
                List<Either<Command, CodeAction>> response = lspClient.sendRequestSync(server -> server.getTextDocumentService().codeAction(params));
                if (response != null) {
                    lsp4jResults.addAll(response);
                }
            }

            for (Either<Command, CodeAction> lsp4jResult : lsp4jResults) {
                CodeAction codeAction = toCodeAction(lsp4jResult);
                // filter out quick fixes when asking for intentions, otherwise quick fixes are added twice
                String kind = codeAction.getKind();
                if (kind == null || !kind.startsWith(CodeActionKind.QuickFix)) {
                    LspIntentionAction intentionAction = codeActionsSupport.createIntentionAction((LspClient) lspClient, codeAction);
                    if (intentionAction != null) {
                        result.add(intentionAction);
                    }
                }
            }
        }
        return result;
    }

    @Override
    public void dispose() {
        for (IntentionAction action : IntentionManager.getInstance().getIntentionActions()) {
            if (IntentionActionDelegate.unwrap(action) instanceof LspIntentionActionWrapper wrapper) {
                // IntentionActions are app-level entities, need to drop project-specific caches
                wrapper.setLspIntentionAction(null);
            }
        }
    }

    public static CodeAction toCodeAction(Either<Command, CodeAction> either) {
        if (either.isLeft()) {
            CodeAction codeAction = new CodeAction();
            codeAction.setTitle(either.getLeft().getTitle());
            codeAction.setCommand(either.getLeft());
            // Non-null `edit` guarantees that the IDE won't send the `codeAction/resolve` request to the server for this `CodeAction`
            // (see `LspIntentionAction.resolvedCodeAction`)
            codeAction.setEdit(new WorkspaceEdit());
            return codeAction;
        }
        return either.getRight();
    }
}

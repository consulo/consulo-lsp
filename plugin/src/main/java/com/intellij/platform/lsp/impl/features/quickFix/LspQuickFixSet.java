package com.intellij.platform.lsp.impl.features.quickFix;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.customization.LspCodeActionsSupport;
import com.intellij.platform.lsp.api.customization.LspIntentionAction;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspDocumentRange;
import com.intellij.platform.lsp.impl.features.intention.LspIntentionActionService;
import consulo.application.Application;
import consulo.application.progress.ProgressManager;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.language.editor.intention.IntentionAction;
import consulo.language.psi.PsiManager;
import consulo.logging.Logger;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.VirtualFileManager;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeActionTriggerKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * IntelliJ Platform API assumes that quick fixes for diagnostics are created right at the moment of the code highlighting. For
 * performance reasons, special {@link LspQuickFixWrapper} are created at that moment. In other words, {@link LspQuickFixWrapper} is a quick fix stub.
 * {@link LspQuickFixWrapper} doesn't contain a {@link CodeAction} object. As soon as the IntelliJ Platform calls {@link LspQuickFixWrapper#getText} or
 * {@link LspQuickFixWrapper#isAvailable}, the {@link LspQuickFixWrapper} asks the LSP server to calculate real {@link CodeAction} and, once received, creates
 * {@link LspIntentionAction} object. After that, {@link LspQuickFixWrapper} delegates {@link IntentionAction#getText}, {@link IntentionAction#isAvailable}, and
 * {@link IntentionAction#invoke} calls to the {@link LspIntentionAction}.
 */
public class LspQuickFixSet {
    private static final Logger LOG = Logger.getInstance(LspQuickFixSet.class);

    private static final int MAX_QUICK_FIXES = 8;

    private final LspClientImpl myLspClient;
    private final VirtualFile myFile;
    private final Diagnostic myDiagnostic;
    private final @Nullable TextDocumentIdentifier myDiagnosticDocumentId;

    private final List<IntentionAction> myQuickFixes;

    private long myPsiModCountWhenRequestSent = 0;
    private long myVfsModCountWhenRequestSent = 0;

    public LspQuickFixSet(LspClientImpl lspClient, VirtualFile file, Diagnostic diagnostic) {
        this(lspClient, file, diagnostic, null);
    }

    public LspQuickFixSet(LspClientImpl lspClient, VirtualFile file, Diagnostic diagnostic, @Nullable TextDocumentIdentifier diagnosticDocumentId) {
        myLspClient = lspClient;
        myFile = file;
        myDiagnostic = diagnostic;
        myDiagnosticDocumentId = diagnosticDocumentId;
        List<IntentionAction> quickFixes = new ArrayList<>(MAX_QUICK_FIXES);
        for (int index = 0; index < MAX_QUICK_FIXES; index++) {
            quickFixes.add(new LspQuickFixWrapper(this, index));
        }
        myQuickFixes = List.copyOf(quickFixes);
    }

    public List<IntentionAction> getQuickFixes() {
        return myQuickFixes;
    }

    public void ensureInitialized() {
        // IntentionAction.text and IntentionAction.isAvailable are normally called from BGT.
        // IntentionAction.invoke is called from EDT, but at that moment, this LspQuickFixWrapper should be already initialized.
        // Anyway, we are not going to wait for the response from the server in EDT.
        if (Application.get().isDispatchThread()) {
            return;
        }

        // A synchronized block is needed to make sure that several threads don't send the same `textDocument/codeAction` request to the server
        // (there may be up to MAX_QUICK_FIXES parallel isAvailable() calls for MAX_QUICK_FIXES LspQuickFixWrapper objects)
        synchronized (this) {
            ProgressManager.checkCanceled();

            long psiModCount = PsiManager.getInstance(myLspClient.getProject()).getModificationTracker().getModificationCount();
            long vfsModCount = VirtualFileManager.getInstance().getModificationCount();
            if (myPsiModCountWhenRequestSent == psiModCount && myVfsModCountWhenRequestSent == vfsModCount) {
                return; // already up-to-date
            }

            for (IntentionAction quickFix : myQuickFixes) {
                ((LspQuickFixWrapper) quickFix).setLspIntentionAction(null);
            }

            CodeActionContext codeActionContext = new CodeActionContext();
            codeActionContext.setOnly(List.of(CodeActionKind.QuickFix));
            codeActionContext.setDiagnostics(List.of(myDiagnostic));
            codeActionContext.setTriggerKind(CodeActionTriggerKind.Automatic);
            CodeActionParams params;
            if (myDiagnosticDocumentId != null) {
                // Cell URI known from diagnostic source — use it directly with the cell-relative range.
                // diagnostic.range is cell-relative here (as received from the server).
                params = new CodeActionParams(myDiagnosticDocumentId, myDiagnostic.getRange(), codeActionContext);
            }
            else {
                // No cell URI — regular text document. diagnostic.range is file-absolute,
                // so getDocumentRanges resolves it to the correct (single) document.
                Document document = FileDocumentManager.getInstance().getDocument(myFile);
                if (document == null) {
                    return;
                }
                List<LspDocumentRange> documentRanges =
                    myLspClient.getDocumentMapping().getDocumentRangesSync(myFile, document, myDiagnostic.getRange());
                if (documentRanges.isEmpty()) {
                    return;
                }
                LspDocumentRange documentRange = documentRanges.get(0);
                params = new CodeActionParams(documentRange.getDocument().getId(), documentRange.getRange(), codeActionContext);
            }
            myLspClient.getRequestExecutor().sendRequestAsyncButWaitForResponseWithCheckCanceled(
                server -> server.getTextDocumentService().codeAction(params),
                lsp4jResults -> {
                    if (psiModCount == PsiManager.getInstance(myLspClient.getProject()).getModificationTracker().getModificationCount() &&
                        vfsModCount == VirtualFileManager.getInstance().getModificationCount()) {
                        myPsiModCountWhenRequestSent = psiModCount;
                        myVfsModCountWhenRequestSent = vfsModCount;

                        if (lsp4jResults != null) {
                            List<CodeAction> codeActions = new ArrayList<>(lsp4jResults.size());
                            for (Either<Command, CodeAction> lsp4jResult : lsp4jResults) {
                                codeActions.add(LspIntentionActionService.toCodeAction(lsp4jResult));
                            }
                            codeActionsReceived(codeActions);
                        }
                    }
                }
            );
        }
    }

    /**
     * Creates {@link LspIntentionAction} objects, to which {@link LspQuickFixWrapper} will delegate {@link IntentionAction#getText},
     * {@link IntentionAction#isAvailable}, and {@link IntentionAction#invoke}
     */
    private void codeActionsReceived(List<CodeAction> codeActions) {
        if (!(myLspClient.getDescriptor().getLspCustomization().getCodeActionsCustomizer() instanceof LspCodeActionsSupport codeActionsSupport)) {
            return;
        }
        int i = 0;
        for (CodeAction codeAction : codeActions) {
            if (i == myQuickFixes.size()) {
                LOG.info("Received " + codeActions.size() + " quick fixes from server, only " + myQuickFixes.size() + " will be handled");
                break;
            }

            LspIntentionAction quickFix = codeActionsSupport.createQuickFix((LspClient) myLspClient, codeAction);
            if (quickFix != null) {
                ((LspQuickFixWrapper) myQuickFixes.get(i++)).setLspIntentionAction(quickFix);
            }
        }
    }

    /**
     * @see LspQuickFixSet
     */
    private static final class LspQuickFixWrapper extends LspIntentionActionWrapperBase {
        private final LspQuickFixSet myQuickFixSet;
        private volatile @Nullable LspIntentionAction myLspIntentionAction;

        private LspQuickFixWrapper(LspQuickFixSet quickFixSet, int index) {
            super(index);
            myQuickFixSet = quickFixSet;
        }

        @Override
        public @Nullable LspIntentionAction getLspIntentionAction() {
            myQuickFixSet.ensureInitialized();
            return myLspIntentionAction;
        }

        @Override
        public void setLspIntentionAction(@Nullable LspIntentionAction lspIntentionAction) {
            myLspIntentionAction = lspIntentionAction;
        }
    }
}

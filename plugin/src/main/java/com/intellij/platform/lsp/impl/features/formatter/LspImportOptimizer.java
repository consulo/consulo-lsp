package com.intellij.platform.lsp.impl.features.formatter;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.customization.LspIntentionAction;
import com.intellij.platform.lsp.api.customization.LspOptimizeImportsCustomizer;
import com.intellij.platform.lsp.api.customization.LspOptimizeImportsSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspDocument;
import com.intellij.platform.lsp.impl.LspDocumentMapping;
import com.intellij.platform.lsp.impl.features.LspExecuteCommandKt;
import com.intellij.platform.lsp.impl.features.intention.LspIntentionActionService;
import com.intellij.platform.lsp.impl.util.LspWorkspaceEditApplier;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.language.Language;
import consulo.language.codeStyle.ExternalFormatProcessor;
import consulo.language.editor.refactoring.ImportOptimizer;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.language.psi.PsiFile;
import consulo.lsp.impl.compat.FormattingService;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeActionTriggerKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * There are two ways in which the Platform may come to {@link LspImportOptimizer}:
 * <ul>
 * <li>via {@code FormattingService.EP_NAME} extension point and {@link LspFormattingService#getImportOptimizers}</li>
 * <li>via {@code com.intellij.formatting.service.CoreFormattingService}
 *   and {@code com.intellij.lang.LanguageImportStatements.INSTANCE} (i.e., via {@link ImportOptimizer} extension point)</li>
 * </ul>
 * The Platform uses one path to calculate {@code com.intellij.codeInsight.actions.OptimizeImportsAction} availability
 * and another path to call {@code com.intellij.codeInsight.actions.OptimizeImportsAction.actionPerformed}.
 * <p>
 * Implementations of {@link LspFormattingService} and {@link LspImportOptimizer} make sure that both paths are 100% equivalent.
 * <p>
 * Consulo: upstream {@code <lang.importOptimizer language="" order="last"/>}. Consulo has only the {@link ImportOptimizer} path
 * ({@link ImportOptimizer#forFile}); this optimizer is registered for {@link Language#ANY} without an order. Consulo's
 * {@code ImportOptimizer.KEY} is built with {@code LanguageOneToMany.build(false)}, so a {@link Language#ANY} optimizer is not found
 * for a file until the platform builds it with {@code true} (see the porting notes). {@link #supports} defers to every other optimizer
 * of the file's language, so the missing {@code last} order does not matter.
 */
@ExtensionImpl
public class LspImportOptimizer implements ImportOptimizer {

    private final Runnable myNoResult = () -> {
    };

    @Override
    public Language getLanguage() {
        return Language.ANY;
    }

    @Override
    @RequiredReadAction
    public boolean supports(PsiFile psiFile) {
        return findClientToOptimizeImports(psiFile) != null;
    }

    @Override
    @RequiredReadAction
    public Runnable processFile(PsiFile psiFile) {
        VirtualFile virtualFile = psiFile.getVirtualFile();
        if (virtualFile == null || virtualFile instanceof VirtualFileWindow) {
            return myNoResult;
        }
        LspClient lspClient = findClientToOptimizeImports(psiFile);
        if (lspClient == null) {
            return myNoResult;
        }

        CodeAction codeAction = requestCodeAction(lspClient, virtualFile);
        if (codeAction == null) {
            return myNoResult;
        }

        LspIntentionAction intentionAction = new LspIntentionAction(lspClient, codeAction);
        if (!intentionAction.isAvailable()) {
            return myNoResult;
        }

        Command command = codeAction.getCommand();
        // If codeAction.edit is null or effectively a no-op, handle Command in a synchronous way (see IJPL-235332)
        if ((codeAction.getEdit() == null || codeAction.getEdit().equals(new WorkspaceEdit())) && command != null) {
            WorkspaceEdit edit = LspExecuteCommandKt.executeCommandExpectingWorkspaceEdit((LspClientImpl) lspClient, command);
            if (edit == null) {
                return myNoResult;
            }
            LspWorkspaceEditApplier applier = LspWorkspaceEditApplier.create(lspClient, edit);
            if (applier == null) {
                return myNoResult;
            }
            return applier::applyWorkspaceEdit;
        }

        return () -> intentionAction.invoke(virtualFile);
    }

    private static @Nullable CodeAction requestCodeAction(LspClient lspClient, VirtualFile virtualFile) {
        CodeActionContext codeActionContext = new CodeActionContext();
        codeActionContext.setDiagnostics(List.of());
        codeActionContext.setTriggerKind(CodeActionTriggerKind.Invoked);
        codeActionContext.setOnly(List.of(CodeActionKind.SourceOrganizeImports));

        // Send code action request per document (cell). For regular files, there's one document.
        List<CodeAction> results = new ArrayList<>();
        for (LspDocument lspDocument : LspDocumentMapping.getDocumentMapping(lspClient).getDocumentsInFileSync(virtualFile)) {
            CodeActionParams params = new CodeActionParams(
                lspDocument.getId(),
                new Range(new Position(0, 0), new Position(0, 0)), // doesn't matter
                codeActionContext
            );

            // Only one code action supported as UI does not suppose selection of the one import
            // option from the list (like it is processed in VS Code)
            List<Either<Command, CodeAction>> codeActions =
                lspClient.sendRequestSync(server -> server.getTextDocumentService().codeAction(params));
            if (codeActions != null && codeActions.size() == 1) {
                results.add(LspIntentionActionService.toCodeAction(codeActions.get(0)));
            }
        }

        return results.isEmpty() ? null : results.get(0);
    }

    @RequiredReadAction
    public static @Nullable LspClient findClientToOptimizeImports(PsiFile psiFile) {
        if (psiFile.getProject().isDefault()) {
            return null;
        }
        VirtualFile virtualFile = psiFile.getVirtualFile();
        if (virtualFile == null || virtualFile instanceof VirtualFileWindow) {
            return null;
        }

        return LspClientManagerImpl.getInstanceImpl(psiFile.getProject()).findRunningClient(lspClient ->
            lspClient.getDescriptor().isSupportedFile(virtualFile) && canClientOptimizeImports(lspClient, psiFile, virtualFile)
        );
    }

    @RequiredReadAction
    private static boolean canClientOptimizeImports(LspClient lspClient, PsiFile psiFile, VirtualFile virtualFile) {
        LspOptimizeImportsCustomizer customizer = lspClient.getDescriptor().getLspCustomization().getOptimizeImportsCustomizer();
        if (!(customizer instanceof LspOptimizeImportsSupport optimizeImportsSupport)) {
            return false;
        }
        // `LspImportOptimizer` should not come to the stage if any other `ImportOptimizer` wants to do its job for this file.
        // Need to check if there's any other `FormattingService`/`ImportOptimizer` that wants to handle this file.
        // `CoreFormattingService` is special, it is registered as the last one, and it iterates `ImportOptimizer` extensions.
        // The following logic is similar to `OptimizeImportsProcessor.collectOptimizers()` and
        // `CoreFormattingService.getImportOptimizers()`, but without checking `LspFormattingService` and `LspImportOptimizer`.
        boolean ideCanOptimizeImportsInThisFileItself =
            doesOtherFormattingServiceWantToWork(psiFile) || doesOtherImportOptimizerWantToWork(psiFile);
        return optimizeImportsSupport
            .shouldOptimizeImportsInThisFileExclusivelyByServer(lspClient, virtualFile, ideCanOptimizeImportsInThisFileItself);
    }

    // The logic is similar to `OptimizeImportsProcessor.collectOptimizers()`,
    // but without checking `LspFormattingService` and `CoreFormattingService`.
    // Consulo: there is no formatting service extension point; the compat formatting services are external format processors.
    @RequiredReadAction
    private static boolean doesOtherFormattingServiceWantToWork(PsiFile psiFile) {
        for (ExternalFormatProcessor processor : ExternalFormatProcessor.EP_NAME.getExtensionList()) {
            if (!(processor instanceof FormattingService service) || service instanceof LspFormattingService) {
                continue;
            }
            if (service.getFeatures().contains(FormattingService.Feature.OPTIMIZE_IMPORTS)
                && service.canFormat(psiFile, FormattingService.Feature.OPTIMIZE_IMPORTS)) {
                for (ImportOptimizer optimizer : service.getImportOptimizers(psiFile)) {
                    if (!(optimizer instanceof LspImportOptimizer) && optimizer.supports(psiFile)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // This function checks all `ImportOptimizers` except `LspImportOptimizer`.
    // The logic is similar to `CoreFormattingService.getImportOptimizers()`, but without checking `LspImportOptimizer`.
    // Consulo: `LanguageImportStatements.INSTANCE.allForLanguageOrAny(language)` - the optimizers of the language (and its base languages)
    // and the ones registered for `Language.ANY`.
    @RequiredReadAction
    private static boolean doesOtherImportOptimizerWantToWork(PsiFile psiFile) {
        List<ImportOptimizer> optimizers = new ArrayList<>(ImportOptimizer.forLanguage(psiFile.getLanguage()));
        optimizers.addAll(ImportOptimizer.forLanguage(Language.ANY));
        for (ImportOptimizer optimizer : optimizers) {
            if (!(optimizer instanceof LspImportOptimizer) && optimizer.supports(psiFile)) {
                return true;
            }
        }
        return false;
    }
}

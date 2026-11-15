// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.navigation;

import com.intellij.platform.lsp.api.customization.LspGoToImplementationSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.component.ExtensionImpl;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.language.pom.PomTargetPsiElement;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.language.psi.search.DefinitionsScopedSearch;
import consulo.language.psi.search.DefinitionsScopedSearchExecutor;
import consulo.project.Project;
import consulo.project.util.query.QueryExecutorBase;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Range;

import java.util.List;
import java.util.function.Predicate;

/**
 * The second half of the Go To Implementation feature for LSP-backed files.
 * <p>
 * Recognizes the {@link LspGotoImplementationTarget} source element that {@link LspImplementationDeclarationSearcher} provided.
 * Sends the
 * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_implementation">textDocument/implementation</a>
 * request and converts the response to PSI elements in the target files.
 */
@ExtensionImpl
public class LspDefinitionsScopedSearcher extends QueryExecutorBase<PsiElement, DefinitionsScopedSearch.SearchParameters>
    implements DefinitionsScopedSearchExecutor {

    public LspDefinitionsScopedSearcher() {
        super(true);
    }

    @Override
    public void processQuery(DefinitionsScopedSearch.SearchParameters queryParameters, Predicate<? super PsiElement> consumer) {
        if (!(queryParameters.getElement() instanceof PomTargetPsiElement sourceElement)) {
            return;
        }
        if (!(sourceElement.getTarget() instanceof LspGotoImplementationTarget target)) {
            return;
        }
        VirtualFile file = target.getPsiFile().getVirtualFile();
        if (file == null) {
            return;
        }
        // SearchParameters has no project
        Project project = target.getPsiFile().getProject();
        PsiManager psiManager = PsiManager.getInstance(project);

        for (LspClientImpl lspClient : LspClientManagerImpl.getInstanceImpl(project).getClientsForFileRequests(file)) {
            if (!LspServerCapabilitiesKt.supportsGotoImplementation(lspClient, file)) {
                continue;
            }
            if (!(lspClient.getDescriptor().getLspCustomization().getGoToImplementationCustomizer() instanceof LspGoToImplementationSupport)) {
                continue;
            }

            List<LocationLink> locationLinks = lspClient.getRequestExecutor().getImplementations(file, target.getOffsetInFile());
            for (LocationLink locationLink : locationLinks) {
                VirtualFile targetFile = lspClient.getDynamicFiles().findTargetFile(locationLink.getTargetUri());
                if (targetFile == null) {
                    continue;
                }
                PsiFile targetPsiFile = psiManager.findFile(targetFile);
                if (targetPsiFile == null) {
                    continue;
                }
                Range range = locationLink.getTargetSelectionRange() != null
                    ? locationLink.getTargetSelectionRange()
                    : locationLink.getTargetRange();
                PsiElement targetElement = targetPsiFile;
                if (range != null) {
                    Document document = FileDocumentManager.getInstance().getDocument(targetFile);
                    Integer offset = document != null ? Lsp4jUtilKt.getOffsetInDocument(document, range.getStart()) : null;
                    if (offset != null) {
                        targetElement = new LspImplementationTargetElement(targetPsiFile, offset);
                    }
                }
                if (!consumer.test(targetElement)) {
                    return;
                }
            }
        }
    }
}

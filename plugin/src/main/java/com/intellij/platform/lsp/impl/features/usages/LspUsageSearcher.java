package com.intellij.platform.lsp.impl.features.usages;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.impl.LspDocument;
import com.intellij.platform.lsp.impl.LspDocumentMapping;
import com.intellij.platform.lsp.impl.LspDocumentPosition;
import com.intellij.platform.lsp.impl.LspResponseMappingKt;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.ReadAction;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.document.util.TextRange;
import consulo.find.FindUsagesHandler;
import consulo.find.FindUsagesHandlerFactory;
import consulo.find.FindUsagesOptions;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.language.psi.scope.GlobalSearchScope;
import consulo.usage.UsageInfo;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * Helps to implement the 'Find Usages' and 'Show Usages' features backed by an LSP server
 * (<a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_references">textDocument/references</a> request).
 * <p>
 * Consulo: IntelliJ's {@code UsageSearcher} (new usages API) is replaced with a {@link FindUsagesHandlerFactory}
 * for the {@link LspSearchTarget} element. The handler reports the {@link LspReferencesQuery} results.
 *
 * @see LspSearchTargetsRule
 */
@ExtensionImpl
public class LspUsageSearcher extends FindUsagesHandlerFactory {
    private static final int FIND_REFERENCES_TIMEOUT_MS = 60_000;

    @Override
    public boolean canFindUsages(PsiElement element) {
        return element instanceof LspSearchTarget;
    }

    @Override
    public @Nullable FindUsagesHandler createFindUsagesHandler(PsiElement element, boolean forHighlightUsages) {
        if (!(element instanceof LspSearchTarget lspSearchTarget)) {
            return null;
        }
        return new LspFindUsagesHandler(lspSearchTarget);
    }

    private static final class LspFindUsagesHandler extends FindUsagesHandler {
        private final LspSearchTarget mySearchTarget;

        private LspFindUsagesHandler(LspSearchTarget searchTarget) {
            super(searchTarget);
            mySearchTarget = searchTarget;
        }

        @Override
        public boolean processElementUsages(PsiElement element, Predicate<UsageInfo> processor, FindUsagesOptions options) {
            return new LspReferencesQuery(mySearchTarget).processResults(processor);
        }

        @Override
        public boolean processUsagesInText(PsiElement element, Predicate<UsageInfo> processor, GlobalSearchScope searchScope) {
            return true;
        }

        @Override
        protected @Nullable Collection<String> getStringsToSearch(PsiElement element) {
            return null;
        }

        @Override
        protected boolean isSearchForTextOccurrencesAvailable(PsiElement psiElement, boolean isSingleFile) {
            return false;
        }
    }

    private static final class LspReferencesQuery {
        private final LspSearchTarget mySearchTarget;

        private LspReferencesQuery(LspSearchTarget searchTarget) {
            mySearchTarget = searchTarget;
        }

        boolean processResults(Predicate<? super UsageInfo> consumer) {
            for (LspClient lspClient : mySearchTarget.getLspClients()) {
                LspDocumentPosition docPosition = ReadAction.compute(() -> {
                    Document document = FileDocumentManager.getInstance().getDocument(mySearchTarget.getFile());
                    if (document == null) {
                        return null;
                    }
                    Integer offset = Lsp4jUtilKt.getOffsetInDocument(document, mySearchTarget.getPosition());
                    if (offset == null) {
                        return null;
                    }
                    return LspDocumentMapping.getDocumentMapping(lspClient).getDocumentPosition(mySearchTarget.getFile(), document, offset);
                });
                if (docPosition == null) {
                    continue;
                }
                TextDocumentIdentifier documentIdentifier = docPosition.getDocument().getId();
                ReferenceContext referenceContext = new ReferenceContext(/* includeDeclaration = */ true);
                ReferenceParams params = new ReferenceParams(documentIdentifier, docPosition.getPosition(), referenceContext);

                List<? extends Location> resultLocations = lspClient.sendRequestSync(
                    FIND_REFERENCES_TIMEOUT_MS,
                    server -> server.getTextDocumentService().references(params)
                );

                if (resultLocations == null || resultLocations.isEmpty()) {
                    continue;
                }

                boolean[] stop = {false};
                ReadAction.run(() -> {
                    PsiManager psiManager = PsiManager.getInstance(lspClient.getProject());

                    for (Location resultLocation : resultLocations) {
                        LspDocument lspDocument = LspDocumentMapping.getDocumentMapping(lspClient).findDocumentByUrl(resultLocation.getUri());
                        Location mappedLocation = lspDocument != null ? LspResponseMappingKt.mapLocation(lspDocument, resultLocation) : resultLocation;
                        VirtualFile resultFile = lspClient.getDescriptor().findFileByUri(mappedLocation.getUri());
                        if (resultFile == null) {
                            continue;
                        }
                        PsiFile resultPsiFile = psiManager.findFile(resultFile);
                        if (resultPsiFile == null) {
                            continue;
                        }
                        TextRange resultRange = Lsp4jUtilKt.getRangeInDocument(resultPsiFile.getFileDocument(), mappedLocation.getRange());
                        if (resultRange == null) {
                            continue;
                        }

                        if (!consumer.test(new UsageInfo(resultPsiFile, resultRange.getStartOffset(), resultRange.getEndOffset(), false))) {
                            stop[0] = true;
                            return;
                        }
                    }
                });
                if (stop[0]) {
                    return false;
                }
            }

            return true;
        }
    }
}

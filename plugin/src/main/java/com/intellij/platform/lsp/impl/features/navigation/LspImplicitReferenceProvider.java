package com.intellij.platform.lsp.impl.features.navigation;

import com.intellij.platform.lsp.localize.LspLocalize;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionCustomizer;
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionSupport;
import com.intellij.platform.lsp.api.customization.LspGoToTypeDefinitionDisabled;
import com.intellij.platform.lsp.api.customization.LspHoverSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.impl.features.usages.LspSearchTarget;
import com.intellij.platform.lsp.impl.features.usages.LspSearchTargetsRule;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.Application;
import consulo.application.ReadAction;
import consulo.application.progress.ProgressManager;
import consulo.codeEditor.Editor;
import consulo.component.ProcessCanceledException;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.document.util.TextRange;
import consulo.language.editor.inject.EditorWindow;
import consulo.language.editor.navigation.GotoDeclarationHandler;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.language.psi.PsiDocumentManager;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.language.impl.psi.FakePsiElement;
import consulo.find.FindManager;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.ex.action.IdeActions;
import consulo.util.lang.function.ThrowableSupplier;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Range;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Used for Go To Declaration and Go To Type Declaration features
 * backed by the information from an LSP server
 * (<a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_definition">textDocument/definition</a> and
 * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_typeDefinition">textDocument/typeDefinition</a>
 * requests).
 * <p>
 * When the caret is on a declaration, the 'Go To Declaration or Usages' action shows the usages instead.
 * See {@link #createResolvedReference}.
 * <p>
 * Consulo: IntelliJ's {@code ImplicitReferenceProvider} does not exist. This class is registered as a
 * {@link GotoDeclarationHandler} (consulted by Go To Declaration and by Ctrl+hover, before the PSI references);
 * Go To Type Declaration reaches {@link #getImplicitReference(PsiFile, int, String, boolean)} through
 * {@link LspTypeDeclarationTargetElementUtilExtender} and {@link LspSymbolTypeProvider}.
 * Ctrl+hover is recognized by the thread: Consulo's {@code CtrlMouseHandler} computes it in a background read action,
 * while a performed action calls the handler on the UI thread, where the LSP request runs under a modal progress.
 */
@ExtensionImpl(id = "LspImplicitReferenceProvider")
public class LspImplicitReferenceProvider implements GotoDeclarationHandler {
    /**
     * IntelliJ's {@code IdeActions.ACTION_GOTO_DECLARATION_ONLY}; Consulo has no such action.
     */
    public static final String ACTION_GOTO_DECLARATION_ONLY = "GotoDeclarationOnly";

    @Override
    @RequiredReadAction
    public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement sourceElement, int offset, Editor editor) {
        if (editor instanceof EditorWindow) {
            return null;
        }
        Project project = editor.getProject();
        if (project == null || project.isDefault()) {
            return null;
        }
        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (psiFile == null) {
            return null;
        }

        boolean isCtrlHover = !Application.get().isDispatchThread();
        LspResolvedSymbolReference reference = getImplicitReference(psiFile, offset, IdeActions.ACTION_GOTO_DECLARATION, isCtrlHover);
        if (reference == null) {
            return null;
        }
        // Consulo's Ctrl+hover underlines the PSI leaf under the pointer, not the reference range.
        // In a file with flat PSI the leaf may span the whole file, so the underline is only shown when the leaf fits the range.
        if (isCtrlHover && (sourceElement == null || !reference.getRangeInElement().contains(sourceElement.getTextRange()))) {
            return null;
        }
        List<PsiElement> results = new ArrayList<>();
        for (PsiElement result : reference.resolveReference()) {
            // Consulo: 'Go To Declaration' navigates to a single target instead of falling back to 'Show Usages' for a target
            // without navigation targets, so the search target is wrapped into an element whose navigation finds the usages.
            results.add(result instanceof LspSearchTarget searchTarget ? new LspShowUsagesElement(searchTarget) : result);
        }
        return results.isEmpty() ? null : results.toArray(PsiElement.EMPTY_ARRAY);
    }

    /**
     * Consulo-only: the self-definition target of 'Go To Declaration'. Navigating to it starts 'Find Usages' of the
     * {@link LspSearchTarget} (Consulo's 'Show Usages' popup is not a public API).
     */
    private static final class LspShowUsagesElement extends FakePsiElement {
        private final LspSearchTarget mySearchTarget;

        private LspShowUsagesElement(LspSearchTarget searchTarget) {
            mySearchTarget = searchTarget;
        }

        @Override
        public Project getProject() {
            return mySearchTarget.getProject();
        }

        @Override
        public PsiManager getManager() {
            return mySearchTarget.getManager();
        }

        @Override
        @RequiredReadAction
        public @Nullable PsiElement getParent() {
            return mySearchTarget.getParent();
        }

        @Override
        public @Nullable PsiFile getContainingFile() {
            return mySearchTarget.getContainingFile();
        }

        @Override
        public boolean isValid() {
            return mySearchTarget.isValid();
        }

        @Override
        public String getName() {
            return mySearchTarget.getName();
        }

        @Override
        public boolean canNavigate() {
            return mySearchTarget.isValid();
        }

        @Override
        public void navigate(boolean requestFocus) {
            FindManager.getInstance(getProject()).findUsages(mySearchTarget, false);
        }
    }

    /**
     * Consulo: upstream {@code getImplicitReference(element, offsetInElement)} with the {@code currentRequest()} passed explicitly.
     *
     * @param actionId     {@link IdeActions#ACTION_GOTO_DECLARATION}, {@link #ACTION_GOTO_DECLARATION_ONLY}
     *                     or {@link IdeActions#ACTION_GOTO_TYPE_DECLARATION}
     * @param isCtrlHover  whether the reference is needed for the Ctrl+hover underline rather than for a performed action
     */
    public @Nullable LspResolvedSymbolReference getImplicitReference(PsiFile psiFile, int offset, String actionId, boolean isCtrlHover) {
        if (psiFile.getProject().isDefault()) {
            return null;
        }
        VirtualFile file = psiFile.getVirtualFile();
        if (file == null) {
            return null;
        }
        if (file instanceof VirtualFileWindow) {
            return null;
        }

        // There are several places in the IntelliJ codebase that call `getImplicitReference()` function.
        // For example, `IdentifierHighlighterPass.highlightReferencesAndDeclarations`, it calls this function on caret movement.
        // No need to send requests to the LSP server for features that won't work anyway.
        // We care only about the "Go To Declaration", "Go To Declaration (only)" and "Go To Type Declaration" actions.
        // We also care about Ctrl+hover, which asks for the same information without performing an action.
        ReferenceRequest request = new ReferenceRequest(actionId, isCtrlHover);
        if (IdeActions.ACTION_GOTO_DECLARATION.equals(request.getActionId()) ||
            ACTION_GOTO_DECLARATION_ONLY.equals(request.getActionId())) {
            boolean fallbackToShowUsagesOnSelfDefinition = IdeActions.ACTION_GOTO_DECLARATION.equals(request.getActionId());
            return computeOffUiThread(psiFile.getProject(), () -> createResolvedReference(
                psiFile, offset, request.isCtrlHover(), this::requestElementDefinitions, fallbackToShowUsagesOnSelfDefinition
            ));
        }
        if (IdeActions.ACTION_GOTO_TYPE_DECLARATION.equals(request.getActionId())) {
            return computeOffUiThread(psiFile.getProject(), () -> createResolvedReference(
                psiFile, offset, request.isCtrlHover(), this::requestTypeDefinitions, false
            ));
        }
        return null;
    }

    /**
     * Consulo: the platform calls the handlers of a performed action on the UI thread.
     * Upstream computes the reference in a background thread under a modal progress; this is the same.
     */
    private static <T extends @Nullable Object> @Nullable T computeOffUiThread(Project project, Supplier<T> computation) {
        if (!Application.get().isDispatchThread()) {
            return computation.get();
        }
        try {
            return ProgressManager.getInstance().runProcessWithProgressSynchronously(
                (ThrowableSupplier<T, RuntimeException>) () -> ReadAction.compute(computation::get),
                LspLocalize.lspResolvingReferenceProgressTitle(),
                true,
                project
            );
        }
        catch (ProcessCanceledException e) {
            return null;
        }
    }

    /**
     * An integration that turns LSP hover off has language support of its own, and that support also answers Ctrl+hover,
     * with a hint such as the TypeScript quick info for ts-go.
     * An LSP reference would replace that answer: the platform skips its own target lookup once any reference is found,
     * and prefers a reference to the declaration under the pointer.
     * So Ctrl+hover only asks the clients that show LSP hover, which is also where the hint of {@link LspDefinitionSymbol}
     * is going to come from (IJPL-252179). A performed action still asks every client.
     */
    private boolean answersCtrlHover(LspClientImpl lspClient) {
        return lspClient.getDescriptor().getLspCustomization().getHoverCustomizer() instanceof LspHoverSupport;
    }

    private List<LocationLink> requestElementDefinitions(LspClientImpl lspClient, VirtualFile file, int offset) {
        if (!LspServerCapabilitiesKt.supportsGotoDefinition(lspClient)) {
            return List.of();
        }
        LspGoToDefinitionCustomizer goToDefCustomizer = lspClient.getDescriptor().getLspCustomization().getGoToDefinitionCustomizer();
        if (!(goToDefCustomizer instanceof LspGoToDefinitionSupport)) {
            return List.of();
        }
        return lspClient.getRequestExecutor().getElementDefinitions(file, offset);
    }

    private List<LocationLink> requestTypeDefinitions(LspClientImpl lspClient, VirtualFile file, int offset) {
        if (!LspServerCapabilitiesKt.supportsGotoTypeDefinition(lspClient)) {
            return List.of();
        }
        if (lspClient.getDescriptor().getLspCustomization().getGoToTypeDefinitionCustomizer() instanceof LspGoToTypeDefinitionDisabled) {
            return List.of();
        }
        return lspClient.getRequestExecutor().getTypeDefinitions(file, offset);
    }

    @FunctionalInterface
    private interface DefinitionsRequest {
        List<LocationLink> send(LspClientImpl lspClient, VirtualFile file, int offset);
    }

    /**
     * Sends the request to the LSP server and returns {@link LspResolvedSymbolReference} based on the received response.
     * <p>
     * When {@code fallbackToShowUsagesOnSelfDefinition} is {@code true} and every response is a self-definition
     * (see {@link #isSelfDefinition}), the caret is on a declaration.
     * In this case the reference resolves to a {@link LspSearchTarget}, which has no navigation targets.
     * The 'Go To Declaration or Usages' action then shows the usages
     * (the <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_references">textDocument/references</a>
     * request), like it does for a declaration in a regular language.
     */
    private @Nullable LspResolvedSymbolReference createResolvedReference(
        PsiFile psiFile,
        int offset,
        boolean isCtrlHover,
        DefinitionsRequest sendRequest,
        boolean fallbackToShowUsagesOnSelfDefinition
    ) {
        VirtualFile file = psiFile.getVirtualFile();
        if (file == null) {
            return null;
        }
        Document document = FileDocumentManager.getInstance().getCachedDocument(file);
        if (document == null) {
            return null;
        }

        List<LspClientImpl> lspClients = new ArrayList<>();
        for (LspClientImpl client : LspClientManagerImpl.getInstanceImpl(psiFile.getProject()).getClientsForFileRequests(file)) {
            if (!isCtrlHover || answersCtrlHover(client)) {
                lspClients.add(client);
            }
        }
        List<LspClientAndLocationLinks> selfDefinitions = new ArrayList<>();
        List<LspClientAndLocationLinks> navigations = new ArrayList<>();
        for (LspClientImpl lspClient : lspClients) {
            List<LocationLink> locationLinks = sendRequest.send(lspClient, file, offset);
            if (locationLinks.isEmpty()) {
                continue;
            }
            LspClientAndLocationLinks response = new LspClientAndLocationLinks(lspClient, locationLinks);
            if (fallbackToShowUsagesOnSelfDefinition && isSelfDefinition(lspClient, locationLinks, file, document, offset)) {
                selfDefinitions.add(response);
            }
            else {
                navigations.add(response);
            }
        }

        if (!navigations.isEmpty()) {
            return buildNavigationReference(psiFile, document, offset, navigations);
        }
        if (selfDefinitions.isEmpty()) {
            return null;
        }
        List<LocationLink> selfDefinitionLinks = new ArrayList<>();
        for (LspClientAndLocationLinks selfDefinition : selfDefinitions) {
            selfDefinitionLinks.addAll(selfDefinition.getLocationLinks());
        }
        return createShowUsagesReference(psiFile, file, document, offset, lspClients, selfDefinitionLinks);
    }

    /**
     * A self-definition means that the server resolved the definition to the request position itself,
     * so the caret is on the declaration.
     * A server that returns a plain {@code Location} gives no origin selection range
     * (see {@code com.intellij.platform.lsp.impl.util.toLocationLink}).
     * For such a response, a definition whose single-line name range covers the caret is a self-definition.
     * A multi-line range is likely the full declaration body, and a usage inside the body must still navigate.
     */
    private boolean isSelfDefinition(
        LspClientImpl lspClient,
        List<LocationLink> locationLinks,
        VirtualFile file,
        Document document,
        int offset
    ) {
        if (locationLinks.size() != 1) {
            return false;
        }
        LocationLink locationLink = locationLinks.get(0);
        if (!Objects.equals(locationLink.getTargetUri(), lspClient.getDescriptor().getFileUri(file))) {
            return false;
        }
        Range targetSelectionRange = locationLink.getTargetSelectionRange();
        if (targetSelectionRange == null) {
            return false;
        }
        Range originSelectionRange = locationLink.getOriginSelectionRange();
        if (originSelectionRange != null) {
            return targetSelectionRange.equals(originSelectionRange);
        }
        if (targetSelectionRange.getStart().getLine() != targetSelectionRange.getEnd().getLine()) {
            return false;
        }
        TextRange targetRangeInDocument = Lsp4jUtilKt.getRangeInDocument(document, targetSelectionRange);
        if (targetRangeInDocument == null) {
            return false;
        }
        return targetRangeInDocument.containsOffset(offset);
    }

    /**
     * The reference-supporting clients come from the same {@code lspClients} list that answered the definition requests.
     * This list can be wider than the one the 'Find Usages' action uses
     * (see {@link LspSearchTargetsRule}):
     * it also covers a content file served by {@code LspDynamicFiles}, which the opened-files registry does not track.
     */
    private @Nullable LspResolvedSymbolReference createShowUsagesReference(
        PsiFile psiFile,
        VirtualFile file,
        Document document,
        int offset,
        Collection<LspClientImpl> lspClients,
        List<LocationLink> selfDefinitionLinks
    ) {
        List<LspClient> referenceClients = new ArrayList<>();
        for (LspClientImpl lspClient : lspClients) {
            if (LspSearchTargetsRule.isFindReferencesEnabledFor(lspClient, file)) {
                referenceClients.add(lspClient);
            }
        }
        if (referenceClients.isEmpty()) {
            return null;
        }

        // The range must contain the request offset: `DeclarationOrReference.Reference.rangeWithOffset` fails otherwise.
        TextRange rangeInFile = new TextRange(offset, offset);
        for (LocationLink link : selfDefinitionLinks) {
            TextRange range = Lsp4jUtilKt.getRangeInDocument(document, link.getTargetSelectionRange());
            if (range != null) {
                rangeInFile = rangeInFile.union(range);
            }
        }

        LspSearchTarget searchTarget = new LspSearchTarget(referenceClients, file, Lsp4jUtilKt.getLsp4jPosition(document, offset));
        return new LspResolvedSymbolReference(psiFile, rangeInFile, List.of(searchTarget));
    }

    private @Nullable LspResolvedSymbolReference buildNavigationReference(
        PsiFile psiFile,
        Document document,
        int offset,
        List<LspClientAndLocationLinks> clientsAndLocationLinks
    ) {
        // In the case of `foo<caret>++`, a server may return references both for `foo` and for `++`.
        // IntelliJ's standard behavior is to respect only the right reference.
        boolean hasRangeToTheRight = false;
        for (LspClientAndLocationLinks clientAndLocationLinks : clientsAndLocationLinks) {
            for (LocationLink locationLink : clientAndLocationLinks.getLocationLinks()) {
                Range originSelectionRange = locationLink.getOriginSelectionRange();
                if (originSelectionRange == null) {
                    continue;
                }
                Integer endOffsetInOrigin = Lsp4jUtilKt.getOffsetInDocument(document, originSelectionRange.getEnd());
                if (endOffsetInOrigin != null && endOffsetInOrigin > offset) {
                    hasRangeToTheRight = true;
                }
            }
        }

        TextRange rangeInFile = null;

        List<PsiElement> resolveResults = new ArrayList<>();
        for (LspClientAndLocationLinks clientAndLocationLinks : clientsAndLocationLinks) {
            for (LocationLink locationLink : clientAndLocationLinks.getLocationLinks()) {
                Range originSelectionRange = locationLink.getOriginSelectionRange();
                TextRange textRange;
                if (originSelectionRange != null) {
                    textRange = Lsp4jUtilKt.getRangeInDocument(document, originSelectionRange);
                    if (textRange == null) {
                        continue;
                    }
                }
                else {
                    textRange = new TextRange(offset, offset);
                }
                if (hasRangeToTheRight && textRange.getEndOffset() <= offset) {
                    // ignore references to the left of the caret
                    continue;
                }
                rangeInFile = rangeInFile != null ? rangeInFile.union(textRange) : textRange;
                VirtualFile targetFile = clientAndLocationLinks.getLspClient().getDynamicFiles().findTargetFile(locationLink.getTargetUri());
                if (targetFile == null) {
                    continue;
                }
                resolveResults.add(new LspDefinitionSymbol(
                    clientAndLocationLinks.getLspClient().getProject(), targetFile, locationLink.getTargetSelectionRange()
                ));
            }
        }

        if (rangeInFile == null || resolveResults.isEmpty()) {
            return null;
        }

        return new LspResolvedSymbolReference(psiFile, rangeInFile, resolveResults);
    }

    private static final class LspClientAndLocationLinks {
        private final LspClientImpl myLspClient;
        private final List<LocationLink> myLocationLinks;

        private LspClientAndLocationLinks(LspClientImpl lspClient, List<LocationLink> locationLinks) {
            myLspClient = lspClient;
            myLocationLinks = locationLinks;
        }

        LspClientImpl getLspClient() {
            return myLspClient;
        }

        List<LocationLink> getLocationLinks() {
            return myLocationLinks;
        }
    }

    private static final class ReferenceRequest {
        private final String myActionId;
        private final boolean myIsCtrlHover;

        private ReferenceRequest(String actionId, boolean isCtrlHover) {
            myActionId = actionId;
            myIsCtrlHover = isCtrlHover;
        }

        String getActionId() {
            return myActionId;
        }

        boolean isCtrlHover() {
            return myIsCtrlHover;
        }
    }

    /**
     * Consulo: upstream {@code PsiSymbolReference}; Consulo has no symbol references, so this is a plain holder.
     */
    public static final class LspResolvedSymbolReference {
        private final PsiFile myPsiFile;
        private final TextRange myRangeInFile;
        private final List<PsiElement> myResolveResults;

        private LspResolvedSymbolReference(PsiFile psiFile, TextRange rangeInFile, List<PsiElement> resolveResults) {
            myPsiFile = psiFile;
            myRangeInFile = rangeInFile;
            myResolveResults = resolveResults;
        }

        public PsiElement getElement() {
            return myPsiFile;
        }

        public TextRange getRangeInElement() {
            return myRangeInFile;
        }

        public List<PsiElement> resolveReference() {
            return myResolveResults;
        }
    }
}

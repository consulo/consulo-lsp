package com.intellij.platform.lsp.impl.features.completion;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.features.documentation.LspDocumentationData;
import consulo.annotation.access.RequiredReadAction;
import consulo.application.ReadAction;
import consulo.application.progress.ProgressManager;
import consulo.language.editor.completion.lookup.LookupElement;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The purpose of this class is to
 * <a href="https://microsoft.github.io/language-server-protocol/specification/#completionItem_resolve">resolve</a>
 * the {@code initialCompletionItem}
 * (used by {@code LspLookupElementDecorator.LspExpensiveRenderer})
 * and to provide documentation for LSP-based completion items.
 * <p>
 * Objects of this class are returned from the {@link LookupElement#getObject} function, thanks to {@link LspLookupElementDecorator}.
 * <p>
 * Consulo: IntelliJ's {@code Pointer}, {@code Symbol} and {@code DocumentationTarget} do not exist in Consulo.
 * {@link com.intellij.platform.lsp.impl.features.documentation.LspDocumentationTargetProvider#getDocumentationElementForLookupItem}
 * selects this object as the documentation target of the corresponding {@link LookupElement} instead.
 */
public class LspCompletionObject {
    private static final long SEMAPHORE_POLL_MS = 10;

    private final LspClientImpl myLspClient;
    private final Semaphore myRequestSemaphore;
    private final CompletionItem myInitialCompletionItem;

    private volatile @Nullable CompletionItem myResolvedCompletionItem;

    public LspCompletionObject(LspClientImpl lspClient, Semaphore requestSemaphore, CompletionItem initialCompletionItem) {
        myLspClient = lspClient;
        myRequestSemaphore = requestSemaphore;
        myInitialCompletionItem = initialCompletionItem;
    }

    public LspClientImpl getLspClient() {
        return myLspClient;
    }

    public CompletionItem getCompletionItem() {
        CompletionItem resolvedCompletionItem = myResolvedCompletionItem;
        return resolvedCompletionItem != null ? resolvedCompletionItem : myInitialCompletionItem;
    }

    /**
     * This function might send the `completionItem/resolve` request to the LSP server and await for the response.
     * Cancelling the waiting (thanks to regular {@link ProgressManager#checkCanceled} calls) triggers cancel notification on LSP.
     * Actual command cancellation support depends on the LSP implementation.
     * <p>
     * Consulo: IntelliJ's function is {@code suspend}; this one waits synchronously (it is called by
     * the expensive lookup element renderer, which Consulo runs on a background thread) and must not be called on the EDT.
     */
    public void resolveCompletionItem() {
        if (myResolvedCompletionItem != null) {
            return;
        }

        if (!isResolveProvider()) {
            myResolvedCompletionItem = myInitialCompletionItem;
            return;
        }
        acquirePermit();
        try {
            CompletionItem rawResolvedCompletionItem =
                myLspClient.sendRequestSync(it -> it.getTextDocumentService().resolveCompletionItem(myInitialCompletionItem));
            if (rawResolvedCompletionItem != null) {
                // LSP spec prohibits using a newly received label
                // TODO revisit all the rest properties
                rawResolvedCompletionItem.setLabel(myInitialCompletionItem.getLabel());
            }
            myResolvedCompletionItem = rawResolvedCompletionItem != null ? rawResolvedCompletionItem : myInitialCompletionItem;
        }
        finally {
            myRequestSemaphore.release();
        }
    }

    // kotlinx `Semaphore.withPermit` suspends; here the permit is awaited with checkCanceled() calls
    private void acquirePermit() {
        while (true) {
            ProgressManager.checkCanceled();
            try {
                if (myRequestSemaphore.tryAcquire(SEMAPHORE_POLL_MS, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    private boolean isResolveProvider() {
        ServerCapabilities serverCapabilities = myLspClient.getServerCapabilities();
        CompletionOptions completionProvider = serverCapabilities != null ? serverCapabilities.getCompletionProvider() : null;
        return completionProvider != null && Boolean.TRUE.equals(completionProvider.getResolveProvider());
    }

    /**
     * IntelliJ's {@code DocumentationTarget.computeDocumentation()}.
     * <p>
     * Consulo: returns the html of the documentation. When the documentation needs the {@code completionItem/resolve} request
     * (IntelliJ returns {@code DocumentationResult.asyncDocumentation} then), the response is awaited synchronously, so this function
     * must not be called on the EDT.
     */
    public @Nullable String computeDocumentation() {
        CompletionItem resolvedCompletionItem = myResolvedCompletionItem;
        if (resolvedCompletionItem != null && resolvedCompletionItem.getDocumentation() != null) {
            return ReadAction.compute(() -> lsp4jDocsToDocumentation(resolvedCompletionItem.getDocumentation()));
        }
        if (myInitialCompletionItem.getDocumentation() != null) {
            return ReadAction.compute(() -> lsp4jDocsToDocumentation(myInitialCompletionItem.getDocumentation()));
        }

        if (resolvedCompletionItem != null) {
            return null;
        }
        if (!isResolveProvider()) {
            return null;
        }

        CompletionItem newlyResolvedCompletionItem =
            myLspClient.sendRequestSync(it -> it.getTextDocumentService().resolveCompletionItem(myInitialCompletionItem));
        myResolvedCompletionItem = newlyResolvedCompletionItem != null ? newlyResolvedCompletionItem : myInitialCompletionItem;
        Either<String, MarkupContent> documentation = Objects.requireNonNull(myResolvedCompletionItem).getDocumentation();
        if (documentation == null) {
            return null;
        }
        return ReadAction.compute(() -> lsp4jDocsToDocumentation(documentation));
    }

    @RequiredReadAction
    private @Nullable String lsp4jDocsToDocumentation(Either<String, MarkupContent> lsp4jDocs) {
        MarkupContent markupContent;
        if (lsp4jDocs.isLeft() && !Objects.requireNonNull(lsp4jDocs.getLeft()).isEmpty()) {
            markupContent = new MarkupContent(MarkupKind.PLAINTEXT, lsp4jDocs.getLeft());
        }
        else if (lsp4jDocs.isRight() && !Objects.requireNonNull(lsp4jDocs.getRight()).getValue().isEmpty()) {
            markupContent = lsp4jDocs.getRight();
        }
        else {
            return null;
        }

        return LspDocumentationData.createLspDocumentationData(markupContent).toQuickDocHtml(myLspClient.getProject());
    }
}

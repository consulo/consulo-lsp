package com.intellij.platform.lsp.impl.features.highlighting;

import com.intellij.platform.lsp.api.LspClient;
import consulo.language.editor.localize.CodeInsightLocalize;
import org.eclipse.lsp4j.DocumentLink;
import org.jspecify.annotations.Nullable;

public class LspDocumentLink {
    private final DocumentLink myInitialDocumentLink;
    private volatile @Nullable DocumentLink myResolvedDocumentLink = null;
    private final @Nullable String myServerTooltip;

    public LspDocumentLink(DocumentLink initialDocumentLink) {
        myInitialDocumentLink = initialDocumentLink;
        myServerTooltip = initialDocumentLink.getTooltip();
    }

    public void resolveDocumentLink(LspClient lspClient) {
        if (myInitialDocumentLink.getTarget() == null && myResolvedDocumentLink == null) {
            DocumentLink resolved = lspClient.sendRequestSync(
                LspClient.DEFAULT_REQUEST_TIMEOUT_MS,
                server -> server.getTextDocumentService().documentLinkResolve(myInitialDocumentLink)
            );
            myResolvedDocumentLink = resolved != null ? resolved : myInitialDocumentLink;
        }
    }

    /**
     * What this link tells the user it does, or {@code null} when the underline alone says it.
     * <p>
     * A URL is labelled the way {@code WebReferenceDocumentationProvider}
     * labels a plain URL reference, rather than by repeating the URL already on screen. A file or directory gets nothing:
     * naming the gesture ("Follow link") only repeats the underline, and the target's path is long and mostly noise.
     */
    public @Nullable String getTooltip() {
        if (myServerTooltip != null) {
            return myServerTooltip;
        }

        String uri = getTargetUri();
        if (uri != null && (uri.startsWith("http://") || uri.startsWith("https://"))) {
            return CodeInsightLocalize.openUrlInBrowserTooltip().get();
        }
        return null;
    }

    public @Nullable String getTargetUri() {
        DocumentLink resolved = myResolvedDocumentLink;
        String target = resolved != null ? resolved.getTarget() : null;
        return target != null ? target : myInitialDocumentLink.getTarget();
    }
}

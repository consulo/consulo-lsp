package com.intellij.platform.lsp.impl.features.highlighting;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspDocument;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspCachedHighlighting;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspHighlightingCache;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspPullResult;
import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.logging.Logger;
import consulo.util.lang.Pair;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_publishDiagnostics">textDocument/publishDiagnostics</a>
 * <p>
 * Odd one out: this cache is populated by unsolicited server notifications, not by pull requests.
 * The pull hooks are overridden to no-ops, and data arrives via {@link #diagnosticsReceived} → {@link #applyServerHighlightings}.
 */
public class LspPublishDiagnosticsCache extends LspHighlightingCache<LspDiagnosticAndLazyQuickFixes> {
    private static final Logger LOG = Logger.getInstance(LspPublishDiagnosticsCache.class);

    private final LspClientImpl myLspClient;
    private final PushedDiagnostics myPushedDiagnostics = new PushedDiagnostics();

    public LspPublishDiagnosticsCache(LspClientImpl lspClient) {
        super(lspClient.getProject());
        myLspClient = lspClient;
    }

    @Override
    public boolean isSupportedForFile(VirtualFile file) {
        return true;
    }

    // The server pushes the data; scheduling a pull would only spawn a doomed no-op coroutine per read.
    @Override
    public boolean getSupportsPull() {
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected CompletableFuture<LspPullResult<LspDiagnosticAndLazyQuickFixes>> sendRequest(VirtualFile file) {
        return CompletableFuture.completedFuture(LspPullResult.Failed.INSTANCE);
    }

    public void diagnosticsReceived(PublishDiagnosticsParams params) {
        // the file is expected to be null if the server is dropping diagnostics for a deleted/renamed/moved file
        LspDocument lspDocument = myLspClient.getDocumentMapping().findDocumentByUrl(params.getUri());
        VirtualFile file = lspDocument != null ? myLspClient.getDescriptor().findFileByUri(lspDocument.getFileUri()) : null;
        if (file == null && !params.getDiagnostics().isEmpty()) {
            // If a file has been deleted / moved / renamed, then the server sends an empty list for an unexistent file -
            // just to drop the client's cache.
            // Not empty diagnostics list for a file that doesn't exist is not expected.
            LOG.warn("Could not find a file with published diagnostics: " + params.getUri());
        }

        Document document = file != null ? FileDocumentManager.getInstance().getCachedDocument(file) : null;
        boolean isUpToDate = file != null && isForCurrentVersion(params, file, document);
        List<Pair<Range, LspDiagnosticAndLazyQuickFixes>> infosFromServer = null;
        if (lspDocument != null && document != null && isUpToDate) {
            infosFromServer = new ArrayList<>(params.getDiagnostics().size());
            for (Diagnostic diagnostic : params.getDiagnostics()) {
                infosFromServer.add(Pair.create(
                    lspDocument.toHostRange(diagnostic.getRange()),
                    new LspDiagnosticAndLazyQuickFixes(diagnostic, lspDocument.getId())
                ));
            }
        }

        VirtualFile previousFile;
        synchronized (this) {
            if (file != null) {
                // The URI resolves to a file.
                if (infosFromServer != null) {
                    // The diagnostics are stored for this file. Another file that held the URI loses it.
                    previousFile = myPushedDiagnostics.putEvicting(params.getUri(), file, infosFromServer);
                }
                else {
                    // The diagnostics are not stored: they are for an old version, or the file has no loaded document.
                    // This file keeps its old diagnostics. Another file that held the URI loses it.
                    previousFile = myPushedDiagnostics.evictFromOtherFile(params.getUri(), file);
                }
            }
            else {
                // The URI resolves to no file, for example after a rename. The file that held the URI loses it.
                previousFile = myPushedDiagnostics.remove(params.getUri());
            }
        }
        if (previousFile != null && previousFile.isValid()) {
            applyDiagnostics(previousFile, FileDocumentManager.getInstance().getCachedDocument(previousFile));
        }

        if (file != null && isUpToDate) {
            applyDiagnostics(file, document);
        }
    }

    /**
     * Returns false when {@code params} are for an older version of the opened {@code document}.
     * The server sends up-to-date diagnostics later.
     */
    private boolean isForCurrentVersion(PublishDiagnosticsParams params, VirtualFile file, @Nullable Document document) {
        Integer declaredVersion = params.getVersion();
        if (declaredVersion == null) {
            return true;
        }
        // We compare versions only for opened files.
        // Assuming that some server even publishes diagnostics for unopened files,
        // those files don't have edits, so mismatched diagnostics won't be perceived as a visual mess.
        if (!myLspClient.isFileOpened(file) || document == null) {
            return true;
        }
        if (myLspClient.getDocumentVersion(document) == declaredVersion) {
            return true;
        }
        myLspClient.logDebug("Ignoring diagnostics (version " + declaredVersion + ") for " + file.getName() + "; " +
            "current document version: " + myLspClient.getDocumentVersion(document));
        return false;
    }

    /**
     * Builds the highlights of {@code file} from its entry in {@link #myPushedDiagnostics}, and refreshes the editor.
     */
    private void applyDiagnostics(VirtualFile file, @Nullable Document document) {
        List<LspCachedHighlighting<LspDiagnosticAndLazyQuickFixes>> cachedHighlightings;
        if (document != null) {
            List<Pair<Range, LspDiagnosticAndLazyQuickFixes>> infos;
            synchronized (this) {
                infos = myPushedDiagnostics.diagnostics(file);
            }
            cachedHighlightings = buildHighlightings(document, infos);
        }
        else {
            // file isn't open => no editor to display diagnostics
            cachedHighlightings = List.of();
        }
        // A push cache never re-pulls, so the snapshot stamp is informational.
        applyServerHighlightings(file, document != null ? document.getModificationStamp() : 0L, cachedHighlightings);
        AppExecutorUtil.getAppExecutorService().execute(() -> onResponseReceived(file));
    }

    @Override
    protected CompletableFuture<@Nullable Void> onResponseReceived(VirtualFile file) {
        LspHighlightingApplier.getInstance(myProject).scheduleHighlightingRefresh(file);
        myLspClient.notifyDiagnosticsReceived(file);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    protected void clearAdditionalCache() {
        myPushedDiagnostics.clear();
    }

    /**
     * The pushed diagnostics of {@link LspPublishDiagnosticsCache}, keyed by LSP document URI and indexed by file.
     * A URI belongs to one file at a time.
     * The class is not thread-safe. The cache calls it under {@code synchronized(cache)}.
     */
    private static final class PushedDiagnostics {
        private final Map<VirtualFile, Map<String, List<Pair<Range, LspDiagnosticAndLazyQuickFixes>>>> myFileToDiagnosticsByDocumentUri =
            new HashMap<>();
        private final Map<String, VirtualFile> myDocumentUriToFile = new HashMap<>();

        List<Pair<Range, LspDiagnosticAndLazyQuickFixes>> diagnostics(VirtualFile file) {
            Map<String, List<Pair<Range, LspDiagnosticAndLazyQuickFixes>>> byUri = myFileToDiagnosticsByDocumentUri.get(file);
            if (byUri == null) {
                return List.of();
            }
            List<Pair<Range, LspDiagnosticAndLazyQuickFixes>> result = new ArrayList<>();
            for (List<Pair<Range, LspDiagnosticAndLazyQuickFixes>> list : byUri.values()) {
                result.addAll(list);
            }
            return result;
        }

        /**
         * Stores {@code diagnostics} of {@code uri} for {@code file}.
         * Evicts {@code uri} from the file that held it before, if that is another file. Returns that file.
         */
        @Nullable
        VirtualFile putEvicting(String uri, VirtualFile file, List<Pair<Range, LspDiagnosticAndLazyQuickFixes>> diagnostics) {
            VirtualFile previous = myDocumentUriToFile.put(uri, file);
            VirtualFile evictedFile = null;
            if (previous != null && !previous.equals(file)) {
                evictedFile = previous;
                removeDiagnosticsFromFile(previous, uri);
            }
            myFileToDiagnosticsByDocumentUri.computeIfAbsent(file, it -> new HashMap<>()).put(uri, diagnostics);
            return evictedFile;
        }

        /**
         * Drops {@code uri} if a file other than {@code file} holds it. Returns that other file.
         * {@code file} keeps its diagnostics for {@code uri}.
         */
        @Nullable
        VirtualFile evictFromOtherFile(String uri, VirtualFile file) {
            VirtualFile holder = myDocumentUriToFile.get(uri);
            if (holder == null || holder.equals(file)) {
                return null;
            }
            return remove(uri);
        }

        /**
         * Drops {@code uri}. Returns the file that held it.
         */
        @Nullable
        VirtualFile remove(String uri) {
            VirtualFile file = myDocumentUriToFile.remove(uri);
            if (file != null) {
                removeDiagnosticsFromFile(file, uri);
            }
            return file;
        }

        void clear() {
            myFileToDiagnosticsByDocumentUri.clear();
            myDocumentUriToFile.clear();
        }

        private void removeDiagnosticsFromFile(VirtualFile file, String uri) {
            Map<String, List<Pair<Range, LspDiagnosticAndLazyQuickFixes>>> diagnosticsByDocumentUri = myFileToDiagnosticsByDocumentUri.get(file);
            if (diagnosticsByDocumentUri == null) {
                throw new IllegalStateException("No diagnostics for " + file);
            }
            diagnosticsByDocumentUri.remove(uri);
            if (diagnosticsByDocumentUri.isEmpty()) {
                myFileToDiagnosticsByDocumentUri.remove(file);
            }
        }
    }
}

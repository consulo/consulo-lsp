// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.lineMarkers;

import com.intellij.platform.lsp.api.customization.LspInheritanceMarker;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspCoroutineScopeService;
import com.intellij.platform.lsp.impl.LspDocumentPosition;
import com.intellij.platform.lsp.localize.LspLocalize;
import com.intellij.platform.lsp.util.LspNavigationUtilsKt;
import consulo.application.ReadAction;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.Task;
import consulo.component.ProcessCanceledException;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.language.editor.gutter.GutterIconNavigationHandler;
import consulo.language.psi.PsiElement;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.event.ComponentEvent;
import consulo.ui.ex.RelativePoint;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.jspecify.annotations.Nullable;

import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Re-requests the targets on click, so the popup shows the current state,
 * and falls back to the targets cached at marker creation.
 * <p>
 * Consulo: {@code cs.launch { withBackgroundProgress { ... }; withContext(EDT) { ... } }} is a {@link Task.Backgroundable}
 * whose {@code onSuccess} (UI thread) shows the targets; the unified {@link ComponentEvent} is converted to the
 * {@link MouseEvent} that {@code navigateOrShowPopup} positions the popup with.
 */
public class LspInheritanceMarkerNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
    private final LspClientImpl myLspClient;
    private final VirtualFile myFile;
    private final int myAnchorStartOffset;
    private final LspInheritanceMarker myMarker;

    public LspInheritanceMarkerNavigationHandler(
        LspClientImpl lspClient,
        VirtualFile file,
        int anchorStartOffset,
        LspInheritanceMarker marker
    ) {
        myLspClient = lspClient;
        myFile = file;
        myAnchorStartOffset = anchorStartOffset;
        myMarker = marker;
    }

    @Override
    @RequiredUIAccess
    public void navigate(ComponentEvent<?> e, PsiElement elt) {
        Project project = myLspClient.getProject();
        LocalizeValue title = popupTitle(myMarker);
        if (LspCoroutineScopeService.getInstance(project).isDisposed()) {
            return;
        }
        MouseEvent mouseEvent = toMouseEvent(e);
        new Task.Backgroundable(project, title, true) {
            private List<Location> myLocations = List.of();

            @Override
            public void run(ProgressIndicator indicator) {
                List<Location> fresh = await(freshTargets(), indicator);
                myLocations = fresh != null ? fresh : myMarker.getTargets();
            }

            @Override
            @RequiredUIAccess
            public void onSuccess() {
                LspNavigationUtilsKt.navigateOrShowPopup(myLspClient, myLocations, title.get(), mouseEvent);
            }
        }.queue();
    }

    private static @Nullable MouseEvent toMouseEvent(ComponentEvent<?> e) {
        if (e.getInputDetails() == null) {
            return null;
        }
        return RelativePoint.fromScreen(e).toMouseEvent();
    }

    /**
     * Waits for the future, cancelling it when the progress is cancelled.
     */
    private static <T> @Nullable T await(CompletableFuture<@Nullable T> future, ProgressIndicator indicator) {
        try {
            while (true) {
                try {
                    indicator.checkCanceled();
                    return future.get(50, TimeUnit.MILLISECONDS);
                }
                catch (TimeoutException ignored) {
                }
            }
        }
        catch (ProcessCanceledException e) {
            future.cancel(true);
            throw e;
        }
        catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProcessCanceledException(e);
        }
        catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(cause);
        }
    }

    private CompletableFuture<@Nullable List<Location>> freshTargets() {
        LspDocumentPosition docPos = ReadAction.compute(() -> {
            Document document = FileDocumentManager.getInstance().getDocument(myFile);
            if (document == null) {
                return null;
            }
            return myLspClient.getDocumentMapping().getDocumentPosition(myFile, document, myAnchorStartOffset);
        });
        if (docPos == null) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<@Nullable List<Location>> targets = switch (myMarker.getKind()) {
            case IMPLEMENTED_METHOD, OVERRIDDEN_METHOD ->
                LspInheritanceMarkersRequestsKt.requestImplementationTargets(myLspClient, docPos.getDocument(), docPos.getPosition());
            case IMPLEMENTED_CLASS, SUBCLASSED_CLASS ->
                LspInheritanceMarkersRequestsKt.requestSubtypeTargets(myLspClient, docPos.getDocument(), docPos.getPosition());
        };

        CompletableFuture<@Nullable List<Location>> result = targets.thenApply(it -> {
            if (it == null) {
                return null;
            }
            Position hostAnchorStart = docPos.getDocument().toHostPosition(docPos.getPosition());
            List<Location> mapped = LspInheritanceMarkersRequestsKt.mapTargetsToHost(myLspClient, docPos.getDocument(), hostAnchorStart, it);
            return mapped.isEmpty() ? null : mapped;
        });
        LspInheritanceMarkersRequestsKt.cancelOnCancellation(result, targets);
        return result;
    }

    private static LocalizeValue popupTitle(LspInheritanceMarker marker) {
        return switch (marker.getKind()) {
            case IMPLEMENTED_METHOD, IMPLEMENTED_CLASS -> LspLocalize.linemarkersPopupTitleImplementations(marker.getSymbolName());
            case OVERRIDDEN_METHOD -> LspLocalize.linemarkersPopupTitleOverrides(marker.getSymbolName());
            case SUBCLASSED_CLASS -> LspLocalize.linemarkersPopupTitleSubtypes(marker.getSymbolName());
        };
    }
}

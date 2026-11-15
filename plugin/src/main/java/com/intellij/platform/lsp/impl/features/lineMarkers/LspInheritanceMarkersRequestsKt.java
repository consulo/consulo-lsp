// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.lineMarkers;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspDocument;
import com.intellij.platform.lsp.impl.LspResponseMappingKt;
import com.intellij.platform.lsp.impl.features.documentSymbol.ConvertersKt;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.TypeHierarchyPrepareParams;
import org.eclipse.lsp4j.TypeHierarchySubtypesParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Inheritance marker requests sent through an {@link LspClientImpl}.
 */
public final class LspInheritanceMarkersRequestsKt {
    private LspInheritanceMarkersRequestsKt() {
    }

    /**
     * Sends {@code textDocument/implementation} and returns the raw targets, or {@code null} when the request failed.
     */
    public static CompletableFuture<@Nullable List<Location>> requestImplementationTargets(
        LspClientImpl lspClient,
        LspDocument lspDocument,
        Position position
    ) {
        ImplementationParams params = new ImplementationParams(lspDocument.getId(), position);
        CompletableFuture<@Nullable Either<List<? extends Location>, List<? extends LocationLink>>> request =
            lspClient.sendRequest(it -> it.getTextDocumentService().implementation(params));
        CompletableFuture<@Nullable List<Location>> result = request.thenApply(response -> {
            if (response == null) {
                return null;
            }
            return response.map(
                locations -> {
                    List<Location> targets = new ArrayList<>(locations.size());
                    for (Location it : locations) {
                        targets.add(new Location(it.getUri(), it.getRange()));
                    }
                    return targets;
                },
                links -> {
                    List<Location> targets = new ArrayList<>(links.size());
                    for (LocationLink it : links) {
                        targets.add(new Location(
                            it.getTargetUri(),
                            it.getTargetSelectionRange() != null ? it.getTargetSelectionRange() : it.getTargetRange()
                        ));
                    }
                    return targets;
                }
            );
        });
        cancelOnCancellation(result, request);
        return result;
    }

    /**
     * Sends {@code textDocument/prepareTypeHierarchy} and {@code typeHierarchy/subtypes}, and returns the raw targets,
     * or {@code null} when a request failed or the position has no hierarchy item.
     */
    public static CompletableFuture<@Nullable List<Location>> requestSubtypeTargets(
        LspClientImpl lspClient,
        LspDocument lspDocument,
        Position position
    ) {
        TypeHierarchyPrepareParams prepareParams = new TypeHierarchyPrepareParams(lspDocument.getId(), position);
        CompletableFuture<@Nullable List<TypeHierarchyItem>> prepareRequest =
            lspClient.sendRequest(it -> it.getTextDocumentService().prepareTypeHierarchy(prepareParams));
        AtomicReference<@Nullable CompletableFuture<?>> subtypesRequestRef = new AtomicReference<>();
        CompletableFuture<@Nullable List<Location>> result = prepareRequest.thenCompose(items -> {
            if (items == null || items.isEmpty()) {
                return CompletableFuture.<@Nullable List<Location>>completedFuture(null);
            }
            TypeHierarchyItem item = items.get(0);
            CompletableFuture<@Nullable List<TypeHierarchyItem>> subtypesRequest =
                lspClient.sendRequest(it -> it.getTextDocumentService().typeHierarchySubtypes(new TypeHierarchySubtypesParams(item)));
            subtypesRequestRef.set(subtypesRequest);
            return subtypesRequest
                .thenApply(subtypes -> {
                    if (subtypes == null) {
                        return null;
                    }
                    List<Location> targets = new ArrayList<>(subtypes.size());
                    for (TypeHierarchyItem it : subtypes) {
                        targets.add(new Location(it.getUri(), it.getSelectionRange() != null ? it.getSelectionRange() : it.getRange()));
                    }
                    return targets;
                });
        });
        cancelOnCancellation(result, prepareRequest);
        result.whenComplete((r, t) -> {
            CompletableFuture<?> subtypesRequest = subtypesRequestRef.get();
            if (result.isCancelled() && subtypesRequest != null) {
                subtypesRequest.cancel(true);
            }
        });
        return result;
    }

    /**
     * A cancelled dependent {@link CompletableFuture} does not cancel the request it depends on ({@code $/cancelRequest}),
     * so the cancellation is forwarded explicitly.
     */
    static void cancelOnCancellation(CompletableFuture<?> dependent, CompletableFuture<?> source) {
        dependent.whenComplete((r, t) -> {
            if (dependent.isCancelled()) {
                source.cancel(true);
            }
        });
    }

    /**
     * Maps the targets to the host file coordinates, and drops the queried symbol itself.
     * Some servers include the queried symbol in the {@code textDocument/implementation} results.
     * A self reference is a same-file target whose range covers the anchor position.
     * The range check also catches a server that reports the full declaration range.
     */
    public static List<Location> mapTargetsToHost(
        LspClientImpl lspClient,
        LspDocument sourceDocument,
        Position hostAnchorStart,
        List<Location> targets
    ) {
        Set<Location> result = new LinkedHashSet<>();
        for (Location target : targets) {
            LspDocument document = lspClient.getDocumentMapping().findDocumentByUrl(target.getUri());
            Location mapped = document != null ? LspResponseMappingKt.mapLocation(document, target) : target;
            if (mapped.getUri().equals(sourceDocument.getFileUri()) && ConvertersKt.containsPosition(mapped.getRange(), hostAnchorStart)) {
                continue;
            }
            result.add(mapped);
        }
        return new ArrayList<>(result);
    }
}

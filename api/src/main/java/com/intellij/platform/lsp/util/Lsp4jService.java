// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.util;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.application.Application;
import org.eclipse.lsp4j.Location;
import org.jspecify.annotations.Nullable;

import java.util.List;

@ServiceAPI(ComponentScope.APPLICATION)
public abstract class Lsp4jService {
    public static Lsp4jService getInstance() {
        return Application.get().getInstance(Lsp4jService.class);
    }

    /**
     * Extracts a list of LSP {@link org.eclipse.lsp4j.Location} objects from command arguments.
     * <p>
     * This function is useful for parsing location data from {@link org.eclipse.lsp4j.Command#getArguments() LSP command arguments}
     * (e.g., for "showReferences" commands). It expects the arguments to follow the pattern:
     * {@code [uri, position, locations[]]} where locations is a JSON array at index 2.
     * <p>
     * Expected argument structure:
     * <pre>
     * [
     *   "file:///path/to/file1.ext",
     *   { "line": 9, "character": 7 },
     *   [
     *     {
     *       "uri": "file:///path/to/file2.ext",
     *       "range": {
     *         "start": { "line": 13, "character": 10 },
     *         "end": { "line": 13, "character": 15 }
     *       }
     *     },
     *     {
     *       "uri": "file:///path/to/file3.ext",
     *       "range": {
     *         "start": { "line": 25, "character": 0 },
     *         "end": { "line": 33, "character": 1 }
     *       }
     *     }
     *   ]
     * ]
     * </pre>
     *
     * @param arguments the command arguments list, typically {@link org.eclipse.lsp4j.Command#getArguments()}
     * @return a list of {@link org.eclipse.lsp4j.Location} objects extracted from the arguments, or null if extraction fails
     * @see org.eclipse.lsp4j.Command#getArguments()
     */
    public abstract @Nullable List<Location> extractLocationsFromJson(@Nullable List<Object> arguments);
}

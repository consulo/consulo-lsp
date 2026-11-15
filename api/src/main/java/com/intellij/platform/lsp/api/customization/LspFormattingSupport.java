// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

import consulo.annotation.access.RequiredReadAction;
import consulo.virtualFileSystem.VirtualFile;

public non-sealed class LspFormattingSupport extends LspFormattingCustomizer {
    /**
     * This function is called on {@code Reformat Code} action, which might be explicit (e.g., on a shortcut)
     * or implicit (e.g., on save, if formatting on save is enabled).
     * <p>
     * If this function returns {@code true} then the IDE will send
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_formatting">textDocument/formatting</a>
     * request to the server and apply the received result.
     * IDE's internal formatter (if it exists) won't be used.
     * <p>
     * If this function returns {@code false} then the IDE will use its internal formatter (if it exists) for this file.
     * <p>
     * Implementation may look like this:
     * <pre>
     *      &#64;Override
     *      public boolean shouldFormatThisFileExclusivelyByServer(VirtualFile file,
     *                                                             boolean ideCanFormatThisFileItself,
     *                                                             boolean serverExplicitlyWantsToFormatThisFile) {
     *        return "foo".equals(file.getExtension());
     *      }
     * </pre>
     *
     * @param file                                  a file to format
     * @param ideCanFormatThisFileItself            technically, this argument says whether {@code LanguageFormatting.forContext}
     *                                              returns not null {@code FormattingModelBuilder} for this {@code file}
     * @param serverExplicitlyWantsToFormatThisFile {@code true} means that the server has sent
     *                                              <a href="https://microsoft.github.io/language-server-protocol/specification/#client_registerCapability">client/registerCapability</a>
     *                                              request to the IDE to register its {@code textDocument/formatting} capability, and the {@code file} matches the provided
     *                                              <a href="https://microsoft.github.io/language-server-protocol/specification/#documentSelector">DocumentSelector</a>.
     *                                              In other words, the server has explicitly said that it wants to format this particular {@code file}.
     *                                              Having only static {@code documentFormattingProvider}
     *                                              <a href="https://microsoft.github.io/language-server-protocol/specification/#serverCapabilities">server capability</a>
     *                                              (within the <a href="https://microsoft.github.io/language-server-protocol/specification/#initializeResult">InitializeResult</a>)
     *                                              doesn't count as an explicit wish to format this specific {@code file}
     */
    @RequiredReadAction
    public boolean shouldFormatThisFileExclusivelyByServer(VirtualFile file,
                                                           boolean ideCanFormatThisFileItself,
                                                           boolean serverExplicitlyWantsToFormatThisFile) {
        return !ideCanFormatThisFileItself && serverExplicitlyWantsToFormatThisFile;
    }
}

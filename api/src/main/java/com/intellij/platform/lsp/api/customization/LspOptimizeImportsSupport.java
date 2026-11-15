// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspServer;
import consulo.annotation.access.RequiredReadAction;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.List;

public non-sealed class LspOptimizeImportsSupport extends LspOptimizeImportsCustomizer {
    /**
     * Determines whether the LSP server should exclusively handle import optimization for the given file.
     * <p>
     * This function is called when the "Optimize Imports" action is invoked, which might be explicit
     * (e.g., on a shortcut) or implicit (e.g., on save or before commit if the corresponding option is enabled).
     * <p>
     * If this function returns {@code true}, the IDE will send a
     * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_codeAction">textDocument/codeAction</a>
     * request with the {@code "source.organizeImports"} code action kind to the server and apply the received result.
     * The IDE's internal import optimizer (if it exists) won't be used.
     * <p>
     * If this function returns {@code false}, the IDE will use its internal import optimizer (if it exists) for this file.
     * <p>
     * The default implementation returns {@code true} only when:
     * <ul>
     * <li>The IDE cannot optimize imports in this file itself, AND</li>
     * <li>The server advertises support for the code action kind {@code "source.organizeImports"}</li>
     * </ul>
     * Implementation may look like this:
     * <pre>
     *    &#64;Override
     *    public boolean shouldOptimizeImportsInThisFileExclusivelyByServer(LspClient lspClient,
     *                                                                     VirtualFile file,
     *                                                                     boolean ideCanOptimizeImportsInThisFileItself) {
     *      return "foo".equals(file.getExtension());
     *    }
     * </pre>
     *
     * @param ideCanOptimizeImportsInThisFileItself {@code true} if the IDE has its own {@code ImportOptimizer}
     *                                              that supports the given file
     */
    @RequiredReadAction
    @SuppressWarnings("deprecation")
    public boolean shouldOptimizeImportsInThisFileExclusivelyByServer(LspClient lspClient,
                                                                      VirtualFile file,
                                                                      boolean ideCanOptimizeImportsInThisFileItself) {
        return shouldOptimizeImportsInThisFileExclusivelyByServer((LspServer) lspClient, file, ideCanOptimizeImportsInThisFileItself);
    }

    /**
     * @deprecated Override or call {@link #shouldOptimizeImportsInThisFileExclusivelyByServer(LspClient, VirtualFile, boolean)} — the LspClient overload
     */
    @Deprecated
    @RequiredReadAction
    @SuppressWarnings("deprecation")
    public boolean shouldOptimizeImportsInThisFileExclusivelyByServer(LspServer lspServer,
                                                                      VirtualFile file,
                                                                      boolean ideCanOptimizeImportsInThisFileItself) {
        if (ideCanOptimizeImportsInThisFileItself) {
            return false;
        }
        InitializeResult initializeResult = lspServer.getInitializeResult();
        ServerCapabilities capabilities = initializeResult != null ? initializeResult.getCapabilities() : null;
        Either<Boolean, CodeActionOptions> codeActionProvider = capabilities != null ? capabilities.getCodeActionProvider() : null;
        CodeActionOptions codeActionOptions = codeActionProvider != null ? codeActionProvider.getRight() : null;
        List<String> codeActionKinds = codeActionOptions != null ? codeActionOptions.getCodeActionKinds() : null;
        if (codeActionKinds == null) {
            return false;
        }
        return codeActionKinds.contains(CodeActionKind.SourceOrganizeImports);
    }
}

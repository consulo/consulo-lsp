// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspServer;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.Command;
import org.jspecify.annotations.Nullable;

import java.awt.event.MouseEvent;

public non-sealed class LspCodeLensSupport extends LspCodeLensCustomizer {
    /**
     * Returns true if the IDE should ask the server for code lenses for the given file.
     */
    public boolean shouldAskServerForCodeLenses(VirtualFile file) {
        return true;
    }

    /**
     * Returns true if the given code lens should be displayed in the editor.
     */
    public boolean shouldDisplayCodeLens(VirtualFile file, CodeLens codeLens) {
        return true;
    }

    /**
     * Called when the user clicks on a code lens (code vision item) in the editor.
     * <p>
     * This method is invoked when a user interacts with a code lens that was previously provided
     * by the LSP server via {@code textDocument/codeLens} requests. The default implementation delegates
     * command execution to {@link LspCommandsSupport#executeCommand} if available.
     *
     * <h2>Usage Example</h2>
     * <p>
     * Override this method to handle language-specific code lens commands.
     * The utility functions {@link com.intellij.platform.lsp.util.Lsp4jService#extractLocationsFromJson} and
     * {@link com.intellij.platform.lsp.util.LspNavigationUtilsKt#navigateOrShowPopup} are provided to handle the most common cases.
     * However, since each language and technology has unique requirements, you can implement custom logic
     * tailored to your LSP server's commands, using the utilities as a reference.
     * <pre>
     * &#64;Override
     * public void codeLensClicked(LspClient lspClient, VirtualFile contextFile, Command command, &#64;Nullable MouseEvent mouseEvent) {
     *   switch (command.getCommand()) {
     *     // Show references: display a popup with clickable locations
     *     case "language.showReferences" -> {
     *       List&lt;Location&gt; locations = Lsp4jService.getInstance().extractLocationsFromJson(command.getArguments());
     *       if (locations == null) return;
     *       LspNavigationUtilsKt.navigateOrShowPopup(lspClient, locations, "References", mouseEvent);
     *     }
     *
     *     // Run: execute a run configuration based on command arguments
     *     case "language.run" -> {
     *       JsonObject runnableArgs = command.getArguments().get(0) instanceof JsonObject o ? o : null;
     *       JsonArray args = runnableArgs != null ? runnableArgs.get("args").getAsJsonArray() : null;
     *       executeRunConfiguration(lspClient.getProject(), args, false);
     *     }
     *
     *     // Test: run tests based on command arguments
     *     case "language.test" -> {
     *       JsonObject testArgs = command.getArguments().get(0) instanceof JsonObject o ? o : null;
     *       String testName = testArgs != null ? testArgs.get("testName").getAsString() : null;
     *       executeTestConfiguration(lspClient.getProject(), testName);
     *     }
     *   }
     * }
     * </pre>
     *
     * @param lspClient   The LSP client instance that provided this code lens
     * @param contextFile The file where the code lens appears
     * @param command     The LSP command to execute, containing command name and arguments
     * @param mouseEvent  The mouse event that triggered the click
     * @see LspCommandsSupport#executeCommand
     * @see com.intellij.platform.lsp.util.Lsp4jService#extractLocationsFromJson
     * @see com.intellij.platform.lsp.util.LspNavigationUtilsKt#navigateOrShowPopup
     */
    @SuppressWarnings("deprecation")
    public void codeLensClicked(LspClient lspClient, VirtualFile contextFile, Command command, @Nullable MouseEvent mouseEvent) {
        codeLensClicked((LspServer) lspClient, contextFile, command, mouseEvent);
    }

    /**
     * @deprecated Override or call {@link #codeLensClicked(LspClient, VirtualFile, Command, MouseEvent)} — the LspClient overload
     */
    @Deprecated
    @SuppressWarnings("deprecation")
    public void codeLensClicked(LspServer server, VirtualFile contextFile, Command command, @Nullable MouseEvent mouseEvent) {
        LspCommandsCustomizer commandsCustomizer = server.getDescriptor().getLspCustomization().getCommandsCustomizer();
        if (commandsCustomizer instanceof LspCommandsSupport commandsSupport) {
            commandsSupport.executeCommand((LspClient) server, contextFile, command);
        }
    }
}

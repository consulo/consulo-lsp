// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.lsp.ui.frontend;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.intellij.lsp.ui.frontend.localize.LspUiLocalize;
import com.intellij.lsp.ui.frontend.settings.LspServerConfiguration;
import com.intellij.platform.lsp.api.LspCommunicationChannel;
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor;
import com.intellij.platform.lsp.api.customization.LspCustomization;
import com.intellij.platform.lsp.api.customization.LspInheritanceMarkersCustomizer;
import com.intellij.platform.lsp.api.customization.LspInheritanceMarkersSupport;
import com.intellij.platform.lsp.api.customization.LspSemanticTokensCustomizer;
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport;
import consulo.language.psi.PsiFile;
import consulo.process.ExecutionException;
import consulo.process.cmd.GeneralCommandLine;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.fileType.FileNameMatcher;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ConfigurableLspClientDescriptor extends ProjectWideLspClientDescriptor {
    private final LspServerConfiguration myConfiguration;

    private final LspCustomization myLspCustomization = new LspCustomization() {
        // user-configured file patterns already gate this server's files,
        // so don't suppress the request for files with a real PSI language (the default TEXT/textmate-only heuristic)
        private final LspSemanticTokensCustomizer mySemanticTokensCustomizer = new LspSemanticTokensSupport() {
            @Override
            public boolean shouldAskServerForSemanticTokens(PsiFile psiFile) {
                return true;
            }
        };

        // A user attaches this server on purpose, so the override/implement gutter icons are on.
        // A plugin integration opts in through its own customization instead.
        private final LspInheritanceMarkersCustomizer myInheritanceMarkersCustomizer = new LspInheritanceMarkersSupport();

        @Override
        public LspSemanticTokensCustomizer getSemanticTokensCustomizer() {
            return mySemanticTokensCustomizer;
        }

        @Override
        public LspInheritanceMarkersCustomizer getInheritanceMarkersCustomizer() {
            return myInheritanceMarkersCustomizer;
        }
    };

    public ConfigurableLspClientDescriptor(Project project, LspServerConfiguration configuration) {
        super(project, configuration.getName());
        myConfiguration = configuration.copy();
    }

    @Override
    public boolean isSupportedFile(VirtualFile file) {
        if (!myConfiguration.getEnabled()) {
            return false;
        }

        List<FileNameMatcher> matchers = myConfiguration.getFileMatchers();
        for (FileNameMatcher matcher : matchers) {
            if (matcher.acceptsCharSequence(file.getName())) {
                return true;
            }
        }

        return false;
    }

    @Override
    public GeneralCommandLine createCommandLine() throws ExecutionException {
        if (myConfiguration.getExecutablePath().isEmpty()) {
            throw new ExecutionException(LspUiLocalize.lspSettingsEmptyExecutableDialog(myConfiguration.getName()).get());
        }

        GeneralCommandLine commandLine = new GeneralCommandLine(myConfiguration.getExecutablePath());

        commandLine.addParameters(myConfiguration.getArgumentsList());

        // Consulo: no remote (Eel) environments, upstream uses getRemoteProjectBaseNioPath() for a non-local project
        VirtualFile[] roots = getRoots();
        Path workingDirectory = roots.length > 0 ? roots[0].toNioPath() : null;
        commandLine.withWorkingDirectory(workingDirectory);

        myConfiguration.getEnvVars().get().configureCommandLine(commandLine, true);

        return commandLine;
    }

    @Override
    public LspCommunicationChannel getLspCommunicationChannel() {
        return switch (myConfiguration.getCommunicationMode()) {
            case STDIO -> LspCommunicationChannel.StdIO.INSTANCE;
            case SOCKET -> new LspCommunicationChannel.Socket(
                myConfiguration.getSocketPort(),
                !myConfiguration.getExecutablePath().isBlank()
            );
        };
    }

    @Override
    public LspCustomization getLspCustomization() {
        return myLspCustomization;
    }

    @Override
    public @Nullable Object createInitializationOptions() {
        if (myConfiguration.getInitializationOptions().isBlank()) {
            return null;
        }

        try {
            return new Gson().fromJson(myConfiguration.getInitializationOptions(), Map.class);
        }
        catch (JsonSyntaxException e) {
            LOG.warn("Invalid JSON in initialization options for '" + myConfiguration.getName() + "': " + e.getMessage());
            return null;
        }
    }
}

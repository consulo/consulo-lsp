// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.fileType.FileNameMatcherFactory;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * The configuration of an LSP server provided by a plugin.
 * <p>
 * The configuration can contain provider defaults or project-specific changes.
 * <p>
 * The class is immutable. To change a value, call {@link #copy}.
 * <ul>
 * <li>{@code name}: the display name of the server.</li>
 * <li>{@code arguments}: command line arguments. Each list item is one argument.</li>
 * <li>{@code filePatterns}: file name patterns. Use {@code *} for any number of characters and {@code ?} for one character.
 * For example, {@code *.lua} matches all Lua files. A pattern matches the file name, not the full path.</li>
 * <li>{@code initializationOptions}: a JSON object sent as the LSP {@code initialize} request options.</li>
 * <li>{@code environmentVariables}: environment variables for the server process.</li>
 * </ul>
 */
public final class LspPluginServerConfiguration {
    private final String myName;
    private final List<String> myArguments;
    private final List<String> myFilePatterns;
    private final String myInitializationOptions;
    private final LspServerEnvironmentData myEnvironmentVariables;

    public LspPluginServerConfiguration(String name) {
        this(name, List.of());
    }

    public LspPluginServerConfiguration(String name, List<String> arguments) {
        this(name, arguments, List.of());
    }

    public LspPluginServerConfiguration(String name, List<String> arguments, List<String> filePatterns) {
        this(name, arguments, filePatterns, "");
    }

    public LspPluginServerConfiguration(String name, List<String> arguments, List<String> filePatterns, String initializationOptions) {
        this(name, arguments, filePatterns, initializationOptions, new LspServerEnvironmentData());
    }

    public LspPluginServerConfiguration(String name,
                                        List<String> arguments,
                                        List<String> filePatterns,
                                        String initializationOptions,
                                        LspServerEnvironmentData environmentVariables) {
        myName = name;
        myArguments = arguments;
        myFilePatterns = filePatterns;
        myInitializationOptions = initializationOptions;
        myEnvironmentVariables = environmentVariables;
    }

    public String getName() {
        return myName;
    }

    public List<String> getArguments() {
        return myArguments;
    }

    public List<String> getFilePatterns() {
        return myFilePatterns;
    }

    public String getInitializationOptions() {
        return myInitializationOptions;
    }

    public LspServerEnvironmentData getEnvironmentVariables() {
        return myEnvironmentVariables;
    }

    /**
     * Returns whether the file matches one of the configured file patterns.
     */
    public boolean isSupportedFile(VirtualFile file) {
        for (String pattern : myFilePatterns) {
            if (!pattern.isBlank() && FileNameMatcherFactory.getInstance().createMatcher(pattern).acceptsCharSequence(file.getName())) {
                return true;
            }
        }
        return false;
    }

    public LspPluginServerConfiguration copy(String name,
                                             List<String> arguments,
                                             List<String> filePatterns,
                                             String initializationOptions,
                                             LspServerEnvironmentData environmentVariables) {
        return new LspPluginServerConfiguration(name, arguments, filePatterns, initializationOptions, environmentVariables);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LspPluginServerConfiguration that)) {
            return false;
        }
        return myName.equals(that.myName) &&
            myArguments.equals(that.myArguments) &&
            myFilePatterns.equals(that.myFilePatterns) &&
            myInitializationOptions.equals(that.myInitializationOptions) &&
            myEnvironmentVariables.equals(that.myEnvironmentVariables);
    }

    @Override
    public int hashCode() {
        return Objects.hash(myName, myArguments, myFilePatterns, myInitializationOptions, myEnvironmentVariables);
    }

    @Override
    public String toString() {
        return "LspPluginServerConfiguration(name=" + myName +
            ", arguments=" + myArguments +
            ", filePatterns=" + myFilePatterns +
            ", initializationOptions=" + myInitializationOptions +
            ", environmentVariables=" + myEnvironmentVariables + ")";
    }
}

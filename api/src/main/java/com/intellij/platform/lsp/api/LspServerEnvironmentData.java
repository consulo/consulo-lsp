// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import consulo.process.cmd.GeneralCommandLine;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Environment variables used to start an LSP server.
 * <p>
 * {@code variables}: custom environment variables. The map is added to the process environment.
 * {@code passParentEnvironment}: whether the process inherits the IDE environment.
 */
public final class LspServerEnvironmentData {
    private final Map<String, String> myVariables;
    private final boolean myPassParentEnvironment;

    public LspServerEnvironmentData() {
        this(Map.of());
    }

    public LspServerEnvironmentData(Map<String, String> variables) {
        this(variables, true);
    }

    public LspServerEnvironmentData(Map<String, String> variables, boolean passParentEnvironment) {
        myVariables = variables;
        myPassParentEnvironment = passParentEnvironment;
    }

    public Map<String, String> getVariables() {
        return myVariables;
    }

    public boolean getPassParentEnvironment() {
        return myPassParentEnvironment;
    }

    /**
     * Applies these environment settings to the command line.
     */
    public GeneralCommandLine configureCommandLine(GeneralCommandLine commandLine) {
        return commandLine
            .withEnvironment(myVariables)
            .withParentEnvironmentType(
                myPassParentEnvironment ? GeneralCommandLine.ParentEnvironmentType.CONSOLE : GeneralCommandLine.ParentEnvironmentType.NONE
            );
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LspServerEnvironmentData that)) {
            return false;
        }
        return myPassParentEnvironment == that.myPassParentEnvironment && myVariables.equals(that.myVariables);
    }

    @Override
    public int hashCode() {
        return Objects.hash(myVariables, myPassParentEnvironment);
    }

    @Override
    public String toString() {
        return "LspServerEnvironmentData(variables=" + myVariables + ", passParentEnvironment=" + myPassParentEnvironment + ")";
    }
}

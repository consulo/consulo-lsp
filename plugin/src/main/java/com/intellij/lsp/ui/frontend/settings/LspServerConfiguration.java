// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.lsp.ui.frontend.settings;

import consulo.process.cmd.ParametersListUtil;
import consulo.util.xml.serializer.annotation.Attribute;
import consulo.util.xml.serializer.annotation.Property;
import consulo.util.xml.serializer.annotation.Tag;
import consulo.virtualFileSystem.fileType.FileNameMatcher;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The XML serializer binds public getter/setter pairs, so the serialization annotations are on the getters.
 */
@Tag("server")
public final class LspServerConfiguration {
    public enum CommunicationMode {
        STDIO,
        SOCKET
    }

    private String myName;
    private boolean myEnabled;
    private String myExecutablePath;
    private EnvironmentVariablesDataOptions myEnvVars;
    private String myArguments;
    private String myFilePatterns;
    private String myInitializationOptions;
    private CommunicationMode myCommunicationMode;
    private int mySocketPort;

    public LspServerConfiguration() {
        this("", true, "", new EnvironmentVariablesDataOptions(), "", "", "", CommunicationMode.STDIO, 0);
    }

    public LspServerConfiguration(String name,
                                  boolean enabled,
                                  String executablePath,
                                  EnvironmentVariablesDataOptions envVars,
                                  String arguments,
                                  String filePatterns,
                                  String initializationOptions,
                                  CommunicationMode communicationMode,
                                  int socketPort) {
        myName = name;
        myEnabled = enabled;
        myExecutablePath = executablePath;
        myEnvVars = envVars;
        myArguments = arguments;
        myFilePatterns = filePatterns;
        myInitializationOptions = initializationOptions;
        myCommunicationMode = communicationMode;
        mySocketPort = socketPort;
    }

    @Attribute("name")
    public String getName() {
        return myName;
    }

    public void setName(@Nullable String name) {
        myName = name == null ? "" : name;
    }

    @Attribute("enabled")
    public boolean getEnabled() {
        return myEnabled;
    }

    public void setEnabled(boolean enabled) {
        myEnabled = enabled;
    }

    @Attribute("executablePath")
    public String getExecutablePath() {
        return myExecutablePath;
    }

    public void setExecutablePath(@Nullable String executablePath) {
        myExecutablePath = executablePath == null ? "" : executablePath;
    }

    @Property(surroundWithTag = false)
    public EnvironmentVariablesDataOptions getEnvVars() {
        return myEnvVars;
    }

    public void setEnvVars(EnvironmentVariablesDataOptions envVars) {
        myEnvVars = envVars;
    }

    @Tag("arguments")
    public String getArguments() {
        return myArguments;
    }

    public void setArguments(@Nullable String arguments) {
        myArguments = arguments == null ? "" : arguments;
    }

    @Tag("filePatterns")
    public String getFilePatterns() {
        return myFilePatterns;
    }

    public void setFilePatterns(@Nullable String filePatterns) {
        myFilePatterns = filePatterns == null ? "" : filePatterns;
    }

    @Tag("initializationOptions")
    public String getInitializationOptions() {
        return myInitializationOptions;
    }

    public void setInitializationOptions(@Nullable String initializationOptions) {
        myInitializationOptions = initializationOptions == null ? "" : initializationOptions;
    }

    @Attribute("communicationMode")
    public CommunicationMode getCommunicationMode() {
        return myCommunicationMode;
    }

    public void setCommunicationMode(CommunicationMode communicationMode) {
        myCommunicationMode = communicationMode;
    }

    @Attribute("socketPort")
    public int getSocketPort() {
        return mySocketPort;
    }

    public void setSocketPort(int socketPort) {
        mySocketPort = socketPort;
    }

    public List<String> getFileExtensions() {
        List<String> result = new ArrayList<>();
        for (String pattern : myFilePatterns.split(";")) {
            String trimmed = pattern.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String extension = trimmed.substring(trimmed.lastIndexOf('.') + 1);
            if (!result.contains(extension)) {
                result.add(extension);
            }
        }
        return result;
    }

    public List<FileNameMatcher> getFileMatchers() {
        List<FileNameMatcher> result = new ArrayList<>();
        for (String pattern : myFilePatterns.split(";")) {
            String trimmed = pattern.trim();
            if (!trimmed.isEmpty()) {
                result.add(LspPatternsPanel.parsePattern(trimmed));
            }
        }
        return result;
    }

    public List<String> getArgumentsList() {
        return ParametersListUtil.parse(myArguments);
    }

    /**
     * A shallow copy: {@link #getEnvVars()} is shared.
     */
    public LspServerConfiguration copy() {
        return new LspServerConfiguration(myName,
            myEnabled,
            myExecutablePath,
            myEnvVars,
            myArguments,
            myFilePatterns,
            myInitializationOptions,
            myCommunicationMode,
            mySocketPort);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LspServerConfiguration that)) {
            return false;
        }
        return myEnabled == that.myEnabled &&
            mySocketPort == that.mySocketPort &&
            myName.equals(that.myName) &&
            myExecutablePath.equals(that.myExecutablePath) &&
            myEnvVars.equals(that.myEnvVars) &&
            myArguments.equals(that.myArguments) &&
            myFilePatterns.equals(that.myFilePatterns) &&
            myInitializationOptions.equals(that.myInitializationOptions) &&
            myCommunicationMode == that.myCommunicationMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(myName,
            myEnabled,
            myExecutablePath,
            myEnvVars,
            myArguments,
            myFilePatterns,
            myInitializationOptions,
            myCommunicationMode,
            mySocketPort);
    }

    @Override
    public String toString() {
        return "LspServerConfiguration(name=" + myName +
            ", enabled=" + myEnabled +
            ", executablePath=" + myExecutablePath +
            ", envVars=" + myEnvVars +
            ", arguments=" + myArguments +
            ", filePatterns=" + myFilePatterns +
            ", initializationOptions=" + myInitializationOptions +
            ", communicationMode=" + myCommunicationMode +
            ", socketPort=" + mySocketPort + ")";
    }
}

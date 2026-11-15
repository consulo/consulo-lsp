// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.lsp.ui.frontend.settings;

import consulo.execution.configuration.EnvironmentVariablesData;
import consulo.util.xml.serializer.annotation.Attribute;
import consulo.util.xml.serializer.annotation.MapAnnotation;
import consulo.util.xml.serializer.annotation.Property;
import consulo.util.xml.serializer.annotation.Tag;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A plain bean; {@code equals} compares the properties.
 */
@Tag("environment")
public final class EnvironmentVariablesDataOptions {
    // user order of env must be preserved - do not sort user input
    private Map<String, String> myEnvs = new LinkedHashMap<>();

    private boolean myPassParentEnvs = true;

    /**
     * Environment variables
     */
    @Property(surroundWithTag = false)
    @MapAnnotation(surroundWithTag = false,
        entryTagName = "env",
        keyAttributeName = "key",
        surroundKeyWithTag = false,
        surroundValueWithTag = false,
        sortBeforeSave = false)
    public Map<String, String> getEnvs() {
        return myEnvs;
    }

    public void setEnvs(Map<String, String> envs) {
        myEnvs = new LinkedHashMap<>(envs);
    }

    @Attribute("isPassParentEnvs")
    public boolean isPassParentEnvs() {
        return myPassParentEnvs;
    }

    public void setPassParentEnvs(boolean passParentEnvs) {
        myPassParentEnvs = passParentEnvs;
    }

    public void set(EnvironmentVariablesData envData) {
        myEnvs.clear();
        myEnvs.putAll(envData.getEnvs());
        myPassParentEnvs = envData.isPassParentEnvs();
    }

    public EnvironmentVariablesData get() {
        return EnvironmentVariablesData.create(myEnvs, myPassParentEnvs);
    }

    public void setEnvironment(Map<String, String> variables, boolean passParentEnvironment) {
        myEnvs.clear();
        myEnvs.putAll(variables);
        myPassParentEnvs = passParentEnvironment;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EnvironmentVariablesDataOptions that)) {
            return false;
        }
        return myPassParentEnvs == that.myPassParentEnvs && myEnvs.equals(that.myEnvs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(myEnvs, myPassParentEnvs);
    }

    @Override
    public String toString() {
        return "EnvironmentVariablesDataOptions(envs=" + myEnvs + ", isPassParentEnvs=" + myPassParentEnvs + ")";
    }
}

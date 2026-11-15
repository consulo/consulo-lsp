// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.lsp.ui.frontend.settings;

import com.intellij.platform.lsp.api.LspIntegrationSettings;
import com.intellij.platform.lsp.api.LspIntegrationSettingsProvider;
import com.intellij.platform.lsp.api.LspPluginServerConfiguration;
import com.intellij.platform.lsp.api.LspServerEnvironmentData;
import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.component.persist.PersistentStateComponent;
import consulo.component.persist.RoamingType;
import consulo.component.persist.State;
import consulo.component.persist.Storage;
import consulo.component.persist.StoragePathMacros;
import consulo.project.Project;
import consulo.util.xml.serializer.XmlSerializerUtil;
import consulo.util.xml.serializer.annotation.AbstractCollection;
import consulo.util.xml.serializer.annotation.MapAnnotation;
import consulo.util.xml.serializer.annotation.Property;
import consulo.util.xml.serializer.annotation.Tag;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consulo: upstream is the project service registered for {@link LspIntegrationSettings}. {@link LspIntegrationSettings} is a
 * project extension in this port (Consulo services can't be optional), and the extension container does not load persistent
 * state, so this class is an internal project service holding the state, and {@link LspIntegrationSettingsExtension} is the
 * registered {@link LspIntegrationSettings} that delegates to it.
 */
@State(
    name = "LspServerSettings",
    storages = @Storage(value = StoragePathMacros.WORKSPACE_FILE, roamingType = RoamingType.DISABLED)
)
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
@Singleton
public class LspIntegrationSettingsImpl implements PersistentStateComponent<LspIntegrationSettingsImpl>, LspIntegrationSettings {
    private List<LspServerConfiguration> myServers = new ArrayList<>();

    private Map<String, PluginServerState> myPluginServers = new LinkedHashMap<>();

    @Tag("servers")
    @AbstractCollection(surroundWithTag = false)
    public List<LspServerConfiguration> getServers() {
        return myServers;
    }

    public void setServers(List<LspServerConfiguration> servers) {
        myServers = servers;
    }

    @Property(surroundWithTag = false)
    @MapAnnotation(surroundWithTag = false,
        entryTagName = "pluginServer",
        keyAttributeName = "id",
        surroundKeyWithTag = false,
        surroundValueWithTag = false,
        sortBeforeSave = false)
    public Map<String, PluginServerState> getPluginServers() {
        return myPluginServers;
    }

    public void setPluginServers(Map<String, PluginServerState> pluginServers) {
        myPluginServers = pluginServers;
    }

    @Override
    public LspIntegrationSettingsImpl getState() {
        return this;
    }

    @Override
    public void loadState(LspIntegrationSettingsImpl state) {
        XmlSerializerUtil.copyBean(state, this);
    }

    @Override
    public LspPluginServerConfiguration getPluginConfiguration(String serverId) {
        LspIntegrationSettingsProvider provider = null;
        for (LspIntegrationSettingsProvider candidate : LspIntegrationSettingsProvider.EP_NAME.getExtensionList()) {
            if (candidate.getServerId().equals(serverId)) {
                provider = candidate;
                break;
            }
        }
        if (provider == null) {
            throw new IllegalStateException("No LSP server settings provider is registered for '" + serverId + "'");
        }
        return getPluginConfiguration(provider);
    }

    public LspPluginServerConfiguration getPluginConfiguration(LspIntegrationSettingsProvider provider) {
        String serverId = provider.getServerId();
        PluginServerState state = myPluginServers.get(serverId);
        LspPluginServerConfiguration defaultConfiguration = provider.getDefaultConfiguration();
        return defaultConfiguration.copy(
            defaultConfiguration.getName(),
            state != null ? List.copyOf(state.getArguments()) : defaultConfiguration.getArguments(),
            state != null ? List.copyOf(state.getFilePatterns()) : defaultConfiguration.getFilePatterns(),
            state != null ? state.getInitializationOptions() : defaultConfiguration.getInitializationOptions(),
            state != null
                ? new LspServerEnvironmentData(state.getEnvironmentVariables().getEnvs(), state.getEnvironmentVariables().isPassParentEnvs())
                : defaultConfiguration.getEnvironmentVariables()
        );
    }

    public void updatePluginConfiguration(String serverId, LspPluginServerConfiguration configuration) {
        PluginServerState state = myPluginServers.get(serverId);
        if (state == null) {
            state = new PluginServerState();
            myPluginServers.put(serverId, state);
        }
        state.setArguments(new ArrayList<>(configuration.getArguments()));
        state.setFilePatterns(new ArrayList<>(configuration.getFilePatterns()));
        state.setInitializationOptions(configuration.getInitializationOptions());
        state.getEnvironmentVariables().setEnvironment(
            configuration.getEnvironmentVariables().getVariables(),
            configuration.getEnvironmentVariables().getPassParentEnvironment()
        );
    }

    @Tag("pluginServerState")
    public static class PluginServerState {
        private List<String> myArguments = new ArrayList<>();

        private List<String> myFilePatterns = new ArrayList<>();

        private String myInitializationOptions = "";

        private EnvironmentVariablesDataOptions myEnvironmentVariables = new EnvironmentVariablesDataOptions();

        @Tag("arguments")
        @AbstractCollection(surroundWithTag = false, elementTag = "argument")
        public List<String> getArguments() {
            return myArguments;
        }

        public void setArguments(List<String> arguments) {
            myArguments = arguments;
        }

        @Tag("filePatterns")
        @AbstractCollection(surroundWithTag = false, elementTag = "pattern")
        public List<String> getFilePatterns() {
            return myFilePatterns;
        }

        public void setFilePatterns(List<String> filePatterns) {
            myFilePatterns = filePatterns;
        }

        @Tag("initializationOptions")
        public String getInitializationOptions() {
            return myInitializationOptions;
        }

        public void setInitializationOptions(@Nullable String initializationOptions) {
            myInitializationOptions = initializationOptions == null ? "" : initializationOptions;
        }

        @Property(surroundWithTag = false)
        public EnvironmentVariablesDataOptions getEnvironmentVariables() {
            return myEnvironmentVariables;
        }

        public void setEnvironmentVariables(EnvironmentVariablesDataOptions environmentVariables) {
            myEnvironmentVariables = environmentVariables;
        }
    }

    public static LspIntegrationSettingsImpl getInstance(Project project) {
        return project.getInstance(LspIntegrationSettingsImpl.class);
    }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.lsp.ui.frontend.settings;

import com.intellij.platform.lsp.api.LspIntegrationProvider;
import com.intellij.platform.lsp.api.LspIntegrationSettingsProvider;
import com.intellij.platform.lsp.api.LspPluginServerConfiguration;
import consulo.configurable.ConfigurationException;
import consulo.configurable.NamedConfigurable;
import consulo.container.plugin.PluginDescriptor;
import consulo.disposer.Disposable;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.Component;
import consulo.ui.Space;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.image.Image;
import consulo.ui.layout.ScrollableLayout;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

public class PluginLspServerNamedConfigurable extends NamedConfigurable<LspPluginServerConfiguration> implements Disposable {
    private final Project myProject;
    private final LspIntegrationSettingsProvider myProvider;
    private final LspPluginServerConfiguration myConfiguration;
    private final PluginDescriptor myPluginDescriptor;
    private final Runnable myUpdateTree;
    private @Nullable PluginLspServerConfigurable myServerConfigurable;

    @RequiredUIAccess
    public PluginLspServerNamedConfigurable(Project project,
                                            LspIntegrationSettingsProvider provider,
                                            LspPluginServerConfiguration configuration,
                                            PluginDescriptor pluginDescriptor,
                                            Runnable updateTree) {
        myProject = project;
        myProvider = provider;
        myConfiguration = configuration;
        myPluginDescriptor = pluginDescriptor;
        myUpdateTree = updateTree;
    }

    @Override
    public void setDisplayName(String name) {
    }

    @Override
    public LspPluginServerConfiguration getEditableObject() {
        return myConfiguration;
    }

    @Override
    public LocalizeValue getBannerSlogan() {
        return LocalizeValue.of(myConfiguration.getName());
    }

    @Override
    public @Nullable Image getIcon() {
        return null;
    }

    @RequiredUIAccess
    @Override
    public Component createOptionsPanel(Disposable parentUIDisposable) {
        Component content = Objects.requireNonNull(getServerConfigurable().createUIComponent(parentUIDisposable));
        content.paddingBuilder().leftSet(Space.LARGE).apply();
        return ScrollableLayout.create(content);
    }

    @Override
    public LocalizeValue getDisplayName() {
        return LocalizeValue.of(myConfiguration.getName());
    }

    @RequiredUIAccess
    @Override
    public void apply() throws ConfigurationException {
        getServerConfigurable().apply();
        myUpdateTree.run();
    }

    @RequiredUIAccess
    @Override
    public boolean isModified() {
        return getServerConfigurable().isModified();
    }

    @RequiredUIAccess
    @Override
    public void reset() {
        getServerConfigurable().reset();
    }

    public @Nullable Class<? extends LspIntegrationProvider> applyConfiguration() throws ConfigurationException {
        if (myServerConfigurable == null) {
            return null;
        }
        LspPluginServerConfiguration edited = myServerConfigurable.getConfiguration();
        LspIntegrationSettingsImpl settings = LspIntegrationSettingsImpl.getInstance(myProject);
        if (edited.equals(settings.getPluginConfiguration(myProvider))) {
            return null;
        }

        settings.updatePluginConfiguration(myProvider.getServerId(), edited);
        return myProvider.getIntegrationProviderClass();
    }

    @RequiredUIAccess
    @Override
    public void dispose() {
        if (myServerConfigurable != null) {
            myServerConfigurable.disposeUIResources();
        }
        myServerConfigurable = null;
    }

    private PluginLspServerConfigurable getServerConfigurable() {
        if (myServerConfigurable == null) {
            myServerConfigurable = new PluginLspServerConfigurable(myProject, myConfiguration, myPluginDescriptor);
        }
        return myServerConfigurable;
    }
}

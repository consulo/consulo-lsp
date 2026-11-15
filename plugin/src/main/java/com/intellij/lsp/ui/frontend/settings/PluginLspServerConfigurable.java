// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.lsp.ui.frontend.settings;

import com.intellij.lsp.ui.frontend.localize.LspUiLocalize;
import com.intellij.platform.lsp.api.LspPluginServerConfiguration;
import com.intellij.platform.lsp.api.LspServerEnvironmentData;
import consulo.application.WriteAction;
import consulo.codeEditor.Editor;
import consulo.codeEditor.EditorFactory;
import consulo.configurable.Configurable;
import consulo.configurable.SimpleConfigurableByProperties;
import consulo.container.plugin.PluginDescriptor;
import consulo.disposer.Disposable;
import consulo.document.Document;
import consulo.execution.configuration.EnvironmentVariablesData;
import consulo.execution.ui.awt.EnvironmentVariablesTextFieldWithBrowseButton;
import consulo.externalService.plugin.PluginsConfigurable;
import consulo.fileChooser.FileChooserTextBoxBuilder;
import consulo.ide.setting.ShowSettingsUtil;
import consulo.localize.LocalizeValue;
import consulo.process.cmd.ParametersListUtil;
import consulo.project.Project;
import consulo.ui.CheckBox;
import consulo.ui.Component;
import consulo.ui.Hyperlink;
import consulo.ui.Label;
import consulo.ui.TextBox;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.layout.LabeledLayout;
import consulo.ui.layout.VerticalLayout;
import consulo.ui.util.FormBuilder;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The settings page of an LSP server provided by a plugin, see {@link LspServerConfigurable}.
 */
public class PluginLspServerConfigurable extends SimpleConfigurableByProperties implements Configurable {
    private final Project myProject;
    private LspPluginServerConfiguration myConfiguration;
    private final PluginDescriptor myPluginDescriptor;
    private @Nullable Editor myInitializationOptionsEditor;

    public PluginLspServerConfigurable(Project project, LspPluginServerConfiguration configuration, PluginDescriptor pluginDescriptor) {
        myProject = project;
        myConfiguration = configuration;
        myPluginDescriptor = pluginDescriptor;
    }

    public LspPluginServerConfiguration getConfiguration() {
        return myConfiguration;
    }

    public void setConfiguration(LspPluginServerConfiguration configuration) {
        myConfiguration = configuration;
    }

    @Override
    public LocalizeValue getDisplayName() {
        return LocalizeValue.of(myConfiguration.getName());
    }

    @RequiredUIAccess
    @Override
    protected Component createLayout(PropertyBuilder propertyBuilder, Disposable uiDisposable) {
        Editor initializationOptionsEditor =
            LspServerConfigurable.createInitializationOptionsEditor(myProject, myConfiguration.getInitializationOptions());
        myInitializationOptionsEditor = initializationOptionsEditor;

        VerticalLayout root = VerticalLayout.create();

        CheckBox enabledBox = CheckBox.create(LspUiLocalize.lspSettingsServerEnable());
        enabledBox.setValue(true);
        enabledBox.setEnabled(false);
        root.add(enabledBox);

        TextBox nameField = TextBox.create(myConfiguration.getName());
        nameField.setEditable(false);
        nameField.setEnabled(false);

        Hyperlink pluginLink = Hyperlink.create(LocalizeValue.ofNullable(myPluginDescriptor.getName()), event ->
            ShowSettingsUtil.getInstance().showAndSelect(myProject, PluginsConfigurable.class, it ->
                it.selectInstalled(myPluginDescriptor.getPluginId())
            )
        );

        root.add(FormBuilder.create()
            .addLabeled(LspUiLocalize.lspSettingsServerName(), nameField)
            .addLabeled(LspUiLocalize.lspSettingsServerProvidedBy(), pluginLink)
            .build());

        FormBuilder group = FormBuilder.create();

        FileChooserTextBoxBuilder.Controller executableField = FileChooserTextBoxBuilder.create(myProject)
            .dialogTitle(LspUiLocalize.lspSettingsServerExecutableBrowse())
            .uiDisposable(uiDisposable)
            .build();
        executableField.setValue(LspUiLocalize.lspSettingsServerExecutableProvidedByPlugin().get());
        executableField.getComponent().setEditable(false);
        executableField.getComponent().setEnabled(false);
        group.addLabeled(LspUiLocalize.lspSettingsServerExecutable(),
            LspServerConfigurable.withComment(executableField.getComponent(), LspUiLocalize.lspSettingsServerExecutableComment()));

        TextBox argumentsField = TextBox.create();
        propertyBuilder.add(argumentsField,
            () -> ParametersListUtil.join(myConfiguration.getArguments()),
            it -> myConfiguration = copy(myConfiguration, ParametersListUtil.parse(it == null ? "" : it), null, null, null));
        group.addLabeled(LspUiLocalize.lspSettingsServerArguments(),
            LspServerConfigurable.withComment(argumentsField, LspUiLocalize.lspSettingsServerArgumentsComment()));

        EnvironmentVariablesTextFieldWithBrowseButton environmentField = new EnvironmentVariablesTextFieldWithBrowseButton();
        propertyBuilder.add(
            () -> {
                EnvironmentVariablesData data = environmentField.getData();
                return new LspServerEnvironmentData(data.getEnvs(), data.isPassParentEnvs());
            },
            environment -> environmentField.setData(EnvironmentVariablesData.create(environment.getVariables(), environment.getPassParentEnvironment())),
            () -> myConfiguration.getEnvironmentVariables(),
            it -> myConfiguration = copy(myConfiguration, null, null, null, it)
        );
        group.addLabeled(LspUiLocalize.lspSettingsServerEnvironmentVariables(), environmentField.getComponent());

        root.add(LabeledLayout.create(LspUiLocalize.lspSettingsServerConfigurationSection(), group.build()));

        root.add(Label.create(LspUiLocalize.lspSettingsServerFilePatterns()));

        LspPatternsPanel patternsPanel = new LspPatternsPanel();
        patternsPanel.setPatterns(String.join(";", myConfiguration.getFilePatterns()));
        propertyBuilder.add(
            () -> {
                List<String> patterns = new ArrayList<>();
                for (String pattern : patternsPanel.getPatterns().split(";")) {
                    if (!pattern.isBlank()) {
                        patterns.add(pattern);
                    }
                }
                return patterns;
            },
            value -> patternsPanel.setPatterns(String.join(";", value)),
            () -> myConfiguration.getFilePatterns(),
            it -> myConfiguration = copy(myConfiguration, null, it, null, null)
        );
        root.add(patternsPanel.getComponent());

        root.add(LspServerConfigurable.withComment(Label.create(LspUiLocalize.lspSettingsServerInit()), LspUiLocalize.lspSettingsServerInitComment()));

        Document initializationOptionsDocument = initializationOptionsEditor.getDocument();
        propertyBuilder.add(
            initializationOptionsDocument::getText,
            text -> WriteAction.run(() -> initializationOptionsDocument.setText(text)),
            () -> myConfiguration.getInitializationOptions(),
            it -> myConfiguration = copy(myConfiguration, null, null, it, null)
        );
        root.add(initializationOptionsEditor.getUIComponent());

        return root;
    }

    @RequiredUIAccess
    @Override
    protected void disposeUIResources(LayoutWrapper component) {
        if (myInitializationOptionsEditor != null) {
            EditorFactory.getInstance().releaseEditor(myInitializationOptionsEditor);
            myInitializationOptionsEditor = null;
        }
    }

    /**
     * Copies the configuration with the given values: {@code null} keeps the current value.
     */
    private static LspPluginServerConfiguration copy(LspPluginServerConfiguration configuration,
                                                     @Nullable List<String> arguments,
                                                     @Nullable List<String> filePatterns,
                                                     @Nullable String initializationOptions,
                                                     @Nullable LspServerEnvironmentData environmentVariables) {
        return configuration.copy(
            configuration.getName(),
            arguments != null ? arguments : configuration.getArguments(),
            filePatterns != null ? filePatterns : configuration.getFilePatterns(),
            initializationOptions != null ? initializationOptions : configuration.getInitializationOptions(),
            environmentVariables != null ? environmentVariables : configuration.getEnvironmentVariables()
        );
    }
}

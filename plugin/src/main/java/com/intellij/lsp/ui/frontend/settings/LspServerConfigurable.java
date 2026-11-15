// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.lsp.ui.frontend.settings;

import com.intellij.lsp.ui.frontend.localize.LspUiLocalize;
import com.intellij.lsp.ui.frontend.settings.LspServerConfiguration.CommunicationMode;
import consulo.application.ReadAction;
import consulo.application.WriteAction;
import consulo.codeEditor.Editor;
import consulo.codeEditor.EditorFactory;
import consulo.configurable.Configurable;
import consulo.configurable.ConfigurationException;
import consulo.configurable.SimpleConfigurableByProperties;
import consulo.disposer.Disposable;
import consulo.document.Document;
import consulo.execution.ui.awt.EnvironmentVariablesTextFieldWithBrowseButton;
import consulo.fileChooser.FileChooserTextBoxBuilder;
import consulo.language.Language;
import consulo.language.plain.PlainTextLanguage;
import consulo.language.psi.PsiDocumentManager;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiFileFactory;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.CheckBox;
import consulo.ui.Component;
import consulo.ui.Label;
import consulo.ui.RadioButton;
import consulo.ui.RadioGroup;
import consulo.ui.TextBox;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.JBUI;
import consulo.ui.layout.HorizontalLayout;
import consulo.ui.layout.LabeledLayout;
import consulo.ui.layout.VerticalLayout;
import consulo.ui.style.ComponentColors;
import consulo.ui.util.FormBuilder;
import consulo.util.io.NetUtil;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A {@link SimpleConfigurableByProperties} with unified UI components; each setting is a {@code PropertyBuilder} property.
 * The project is always local, so the socket mode is always supported and the executable is chosen with the local file chooser.
 */
public class LspServerConfigurable extends SimpleConfigurableByProperties implements Configurable {
    private static final String STDIO_ARGUMENT = "--stdio";
    private static final String SOCKET_ARGUMENT = "--socket";
    private static final Pattern MODE_ARGUMENT_REGEX = Pattern.compile("--stdio|--socket(=\\S*)?");

    private final Project myProject;
    private final LspServerConfiguration myConfiguration;
    private @Nullable Editor myInitializationOptionsEditor;

    public LspServerConfigurable(Project project, LspServerConfiguration configuration) {
        myProject = project;
        myConfiguration = configuration;
    }

    @RequiredUIAccess
    @Override
    protected Component createLayout(PropertyBuilder propertyBuilder, Disposable uiDisposable) {
        // the project is always local
        boolean isSocketSupported = true;
        if (!isSocketSupported && myConfiguration.getCommunicationMode() == CommunicationMode.SOCKET) {
            myConfiguration.setCommunicationMode(CommunicationMode.STDIO);
            myConfiguration.setArguments(replaceCommunicationModeArgument(myConfiguration.getArguments(), STDIO_ARGUMENT));
            myConfiguration.setSocketPort(0);
        }

        Editor initializationOptionsEditor = createInitializationOptionsEditor(myProject, myConfiguration.getInitializationOptions());
        myInitializationOptionsEditor = initializationOptionsEditor;

        VerticalLayout root = VerticalLayout.create();

        CheckBox enabledBox = CheckBox.create(LspUiLocalize.lspSettingsServerEnable());
        propertyBuilder.add(enabledBox, myConfiguration::getEnabled, myConfiguration::setEnabled);
        root.add(enabledBox);

        TextBox nameField = TextBox.create();
        propertyBuilder.add(nameField, myConfiguration::getName, myConfiguration::setName);
        root.add(FormBuilder.create()
            .addLabeled(LspUiLocalize.lspSettingsServerName(), withComment(nameField, LspUiLocalize.lspSettingsServerNameComment()))
            .build());

        FormBuilder group = FormBuilder.create();

        RadioGroup<CommunicationMode> modeGroup = RadioGroup.create();
        RadioButton stdioRadioButton = modeGroup.newButton(LspUiLocalize.lspSettingsServerModeStdio(), CommunicationMode.STDIO);
        RadioButton socketRadioButton = modeGroup.newButton(LspUiLocalize.lspSettingsServerModeSocket(), CommunicationMode.SOCKET);
        stdioRadioButton.setEnabled(isSocketSupported);
        socketRadioButton.setEnabled(isSocketSupported);
        propertyBuilder.add(
            modeGroup::getValue,
            // without listeners, so a programmatic selection during reset() doesn't rewrite stored arguments
            mode -> modeGroup.setValue(mode, false),
            myConfiguration::getCommunicationMode,
            mode -> myConfiguration.setCommunicationMode(mode == null ? CommunicationMode.STDIO : mode)
        );
        HorizontalLayout modeRow = HorizontalLayout.create();
        modeRow.add(stdioRadioButton);
        modeRow.add(isSocketSupported ? socketRadioButton : withComment(socketRadioButton, LspUiLocalize.lspSettingsServerModeSocketRemoteUnsupported()));
        group.addLabeled(LspUiLocalize.lspSettingsServerMode(), modeRow);

        FileChooserTextBoxBuilder.Controller executablePathField = createExecutablePathField(uiDisposable);
        propertyBuilder.add(executablePathField::getValue, executablePathField::setValue, myConfiguration::getExecutablePath, myConfiguration::setExecutablePath);
        group.addLabeled(LspUiLocalize.lspSettingsServerExecutable(),
            withComment(executablePathField.getComponent(), LspUiLocalize.lspSettingsServerExecutableComment()));

        TextBox argumentsField = TextBox.create();
        propertyBuilder.add(argumentsField, myConfiguration::getArguments, myConfiguration::setArguments);
        group.addLabeled(LspUiLocalize.lspSettingsServerArguments(), withComment(argumentsField, LspUiLocalize.lspSettingsServerArgumentsComment()));

        EnvironmentVariablesTextFieldWithBrowseButton environmentField = new EnvironmentVariablesTextFieldWithBrowseButton();
        propertyBuilder.add(environmentField::getData,
            environmentField::setData,
            () -> myConfiguration.getEnvVars().get(),
            data -> myConfiguration.getEnvVars().set(data));
        group.addLabeled(LspUiLocalize.lspSettingsServerEnvironmentVariables(), environmentField.getComponent());

        // value listeners of the group fire only on user interaction (reset() selects without listeners),
        // so programmatic selection during reset() doesn't rewrite stored arguments
        modeGroup.addValueListener(mode -> {
            if (mode == CommunicationMode.STDIO) {
                argumentsField.setValue(replaceCommunicationModeArgument(argumentsField.getValueOrError(), STDIO_ARGUMENT));
            }
            else if (mode == CommunicationMode.SOCKET) {
                Integer parsedPort = parseSocketPortArgument(argumentsField.getValueOrError());
                int port;
                if (parsedPort != null && parsedPort >= 1 && parsedPort <= 65535) {
                    port = parsedPort;
                }
                else if (myConfiguration.getSocketPort() >= 1 && myConfiguration.getSocketPort() <= 65535) {
                    port = myConfiguration.getSocketPort();
                }
                else {
                    try {
                        port = NetUtil.findAvailableSocketPort();
                    }
                    catch (IOException ignored) {
                        port = 0;
                    }
                }
                argumentsField.setValue(replaceCommunicationModeArgument(argumentsField.getValueOrError(), SOCKET_ARGUMENT + "=" + port));
            }
        });

        root.add(LabeledLayout.create(LspUiLocalize.lspSettingsServerConfigurationSection(), group.build()));

        root.add(Label.create(LspUiLocalize.lspSettingsServerFilePatterns()));

        LspPatternsPanel patternsPanel = new LspPatternsPanel();
        patternsPanel.setPatterns(myConfiguration.getFilePatterns());
        propertyBuilder.add(patternsPanel::getPatterns, patternsPanel::setPatterns, myConfiguration::getFilePatterns, myConfiguration::setFilePatterns);
        root.add(patternsPanel.getComponent());

        root.add(withComment(Label.create(LspUiLocalize.lspSettingsServerInit()), LspUiLocalize.lspSettingsServerInitComment()));

        Document initializationOptionsDocument = initializationOptionsEditor.getDocument();
        propertyBuilder.add(
            initializationOptionsDocument::getText,
            text -> WriteAction.run(() -> initializationOptionsDocument.setText(text)),
            myConfiguration::getInitializationOptions,
            myConfiguration::setInitializationOptions
        );
        root.add(initializationOptionsEditor.getUIComponent());

        return root;
    }

    @RequiredUIAccess
    @Override
    protected void apply(LayoutWrapper component) throws ConfigurationException {
        super.apply(component);

        // after the bound properties are applied
        Integer port = parseSocketPortArgument(myConfiguration.getArguments());
        myConfiguration.setSocketPort(port == null ? 0 : port);
    }

    @RequiredUIAccess
    private FileChooserTextBoxBuilder.Controller createExecutablePathField(Disposable uiDisposable) {
        return FileChooserTextBoxBuilder.create(myProject)
            .dialogTitle(LspUiLocalize.lspSettingsServerExecutableBrowse())
            .uiDisposable(uiDisposable)
            .build();
    }

    @Override
    public LocalizeValue getDisplayName() {
        return myConfiguration.getName().isEmpty() ? LspUiLocalize.lspSettingsDefaultName() : LocalizeValue.of(myConfiguration.getName());
    }

    @RequiredUIAccess
    @Override
    protected void disposeUIResources(LayoutWrapper component) {
        if (myInitializationOptionsEditor != null) {
            EditorFactory.getInstance().releaseEditor(myInitializationOptionsEditor);
            myInitializationOptionsEditor = null;
        }
        super.disposeUIResources(component);
    }

    /**
     * JSON support is a separate plugin, so the language is looked up by id and plain text is used without it.
     */
    @RequiredUIAccess
    static Editor createInitializationOptionsEditor(Project project, String text) {
        Language jsonLanguage = Language.findLanguageByID("JSON");
        PsiFile jsonFile = PsiFileFactory.getInstance(project).createFileFromText(
            "dummy.json",
            jsonLanguage != null ? jsonLanguage : PlainTextLanguage.INSTANCE,
            text,
            true,
            false
        );
        Document jsonDocument = ReadAction.compute(() -> PsiDocumentManager.getInstance(project).getDocument(jsonFile));
        if (jsonDocument == null) {
            jsonDocument = EditorFactory.getInstance().createDocument(text);
        }
        Editor editor = jsonFile.getVirtualFile() != null
            ? EditorFactory.getInstance().createEditor(jsonDocument, project, jsonFile.getVirtualFile(), false)
            : EditorFactory.getInstance().createEditor(jsonDocument, project);
        editor.getSettings().setAdditionalLinesCount(0);
        editor.getComponent().setPreferredSize(JBUI.size(200, 100));
        return editor;
    }

    /**
     * Adds a grayed comment text under the component.
     */
    @RequiredUIAccess
    static Component withComment(Component component, LocalizeValue comment) {
        VerticalLayout layout = VerticalLayout.create();
        layout.add(component);
        Label commentLabel = Label.create(comment);
        commentLabel.setForegroundColor(ComponentColors.DISABLED_TEXT);
        layout.add(commentLabel);
        return layout;
    }

    // @VisibleForTesting
    public static @Nullable Integer parseSocketPortArgument(String arguments) {
        for (String token : arguments.split(" ", -1)) {
            if (token.startsWith(SOCKET_ARGUMENT + "=")) {
                try {
                    return Integer.parseInt(token.substring(token.indexOf('=') + 1));
                }
                catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    // @VisibleForTesting
    public static String replaceCommunicationModeArgument(String arguments, String newArgument) {
        List<String> tokens = new ArrayList<>();
        for (String token : arguments.split(" ", -1)) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        List<String> result = new ArrayList<>(tokens.size() + 1);
        boolean replaced = false;
        for (String token : tokens) {
            if (MODE_ARGUMENT_REGEX.matcher(token).matches()) {
                if (!replaced) {
                    result.add(newArgument);
                    replaced = true;
                }
            }
            else {
                result.add(token);
            }
        }
        if (!replaced) {
            result.add(newArgument);
        }
        return String.join(" ", result);
    }
}

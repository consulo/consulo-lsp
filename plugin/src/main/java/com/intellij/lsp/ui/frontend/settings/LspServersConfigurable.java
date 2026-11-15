// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.lsp.ui.frontend.settings;

import com.intellij.lsp.ui.frontend.ConfigurableLspIntegrationProvider;
import com.intellij.lsp.ui.frontend.localize.LspUiLocalize;
import com.intellij.platform.lsp.api.LspClientManager;
import com.intellij.platform.lsp.api.LspIntegrationProvider;
import com.intellij.platform.lsp.api.LspIntegrationSettingsProvider;
import com.intellij.platform.lsp.api.LspPluginServerConfiguration;
import consulo.annotation.component.ExtensionImpl;
import consulo.configurable.ConfigurationException;
import consulo.configurable.NamedConfigurable;
import consulo.configurable.ProjectConfigurable;
import consulo.configurable.SearchableConfigurable;
import consulo.configurable.Settings;
import consulo.configurable.StandardConfigurableIds;
import consulo.container.plugin.PluginDescriptor;
import consulo.container.plugin.PluginManager;
import consulo.dataContext.DataManager;
import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.externalService.plugin.PluginsConfigurable;
import consulo.fileEditor.EditorNotifications;
import consulo.ide.setting.ShowSettingsUtil;
import consulo.localize.LocalizeValue;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.Project;
import consulo.ui.Alerts;
import consulo.ui.Component;
import consulo.ui.Hyperlink;
import consulo.ui.Space;
import consulo.ui.UIAccess;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnAction;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.AnActionWithSyncUpdate;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.ui.ex.awt.JBUI;
import consulo.ui.ex.awt.MasterDetailsComponent;
import consulo.ui.ex.awt.MasterDetailsStateService;
import consulo.ui.ex.awtUnsafe.TargetAWT;
import consulo.ui.image.Image;
import consulo.ui.layout.ScrollableLayout;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jspecify.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * There is no "Languages &amp; Frameworks" group, the page is placed under {@link StandardConfigurableIds#EDITOR_GROUP}.
 */
@ExtensionImpl
public class LspServersConfigurable extends MasterDetailsComponent implements SearchableConfigurable, ProjectConfigurable {
    private final Project myProject;
    private final LspIntegrationSettingsImpl mySettings;
    private @Nullable JComponent myMorePluginsLinkPanel;
    // The configurable is a project extension and is reused, so the node configurables are registered in a child
    // disposable that is disposed in disposeUIResources()
    private @Nullable Disposable myDisposable;

    @Inject
    public LspServersConfigurable(Project project,
                                  Provider<MasterDetailsStateService> masterDetailsStateService,
                                  LspIntegrationSettingsImpl settings) {
        super(masterDetailsStateService);
        myProject = project;
        mySettings = settings;
        initTree();
    }

    @Override
    public String getId() {
        return "lsp.servers";
    }

    @Override
    public @Nullable String getParentId() {
        return StandardConfigurableIds.EDITOR_GROUP;
    }

    @Override
    public LocalizeValue getDisplayName() {
        return LspUiLocalize.lspSettingsName();
    }

    @Override
    protected @Nullable String getEmptySelectionString() {
        return LspUiLocalize.lspSettingsEmptySelection().get();
    }

    @Override
    protected void reInitWholePanelIfNeeded() {
        super.reInitWholePanelIfNeeded();

        // called from the super constructor too, before the fields are initialized
        if (mySplitter == null || myTree == null) {
            return;
        }
        JComponent leftPanel = (JComponent) mySplitter.getFirstComponent();
        if (leftPanel == null) {
            return;
        }
        JComponent morePluginsLinkPanel = getMorePluginsLinkPanel();
        if (morePluginsLinkPanel.getParent() != leftPanel) {
            leftPanel.add(morePluginsLinkPanel, BorderLayout.SOUTH);
        }
    }

    @RequiredUIAccess
    private JComponent getMorePluginsLinkPanel() {
        if (myMorePluginsLinkPanel == null) {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setOpaque(false);
            panel.setBorder(JBUI.Borders.empty(8, 20, 8, 0));
            Hyperlink link = Hyperlink.create(LspUiLocalize.lspSettingsMorePlugins(), event -> showMorePlugins());
            panel.add(TargetAWT.to(link), BorderLayout.WEST);
            myMorePluginsLinkPanel = panel;
        }
        return myMorePluginsLinkPanel;
    }

    /**
     * The plugin manager has no marketplace tag search, the link only opens the plugin manager.
     */
    @RequiredUIAccess
    private void showMorePlugins() {
        Settings allSettings = DataManager.getInstance().getDataContext(myTree).getData(Settings.KEY);
        if (allSettings == null) {
            ShowSettingsUtil.getInstance().showAndSelect(myProject, PluginsConfigurable.class);
        }
        else {
            allSettings.select(PluginsConfigurable.CONFIGURABLE_ID);
        }
    }

    @Override
    protected @Nullable List<AnAction> createActions(boolean fromPopup) {
        return List.of(new AddAction(), new MyDeleteAction());
    }

    private class AddAction extends DumbAwareAction {
        private AddAction() {
            super(LspUiLocalize.lspSettingsActionAdd(), LspUiLocalize.lspSettingsActionAddDescription(), PlatformIconGroup.generalAdd());
        }

        @RequiredUIAccess
        @Override
        public void actionPerformed(AnActionEvent e) {
            LspServerConfiguration newConfig = new LspServerConfiguration();
            newConfig.setName(generateUniqueName());
            newConfig.setArguments("--stdio");
            MyNode node = addServerNode(newConfig);
            selectNodeInTree(node);
        }
    }

    private class MyDeleteAction extends DumbAwareAction implements AnActionWithSyncUpdate {
        private MyDeleteAction() {
            super(LspUiLocalize.lspSettingsActionDelete(), LspUiLocalize.lspSettingsActionDeleteDescription(), PlatformIconGroup.generalRemove());
        }

        @RequiredUIAccess
        @Override
        public void actionPerformed(AnActionEvent e) {
            MyNode selected = getSelectedNode();
            if (selected == null) {
                return;
            }
            if (!(selected.getConfigurable() instanceof LspServerNamedConfigurable configurable)) {
                return;
            }
            String serverName = configurable.getDisplayName().get();

            // the alert is asynchronous
            UIAccess uiAccess = UIAccess.current();
            Alerts.yesNo()
                .asQuestion()
                .title(LspUiLocalize.lspSettingsDialogRemoveServerTitle())
                .text(LspUiLocalize.lspSettingsDialogRemoveServerMessage(serverName))
                .showAsync(TargetAWT.wrap(myTree))
                .thenAccept(result -> {
                    if (Boolean.TRUE.equals(result)) {
                        uiAccess.give(() -> {
                            TreePath selectionPath = myTree.getSelectionPath();
                            if (selectionPath == null) {
                                return;
                            }
                            removePaths(selectionPath);
                        });
                    }
                });
        }

        @Override
        public void update(AnActionEvent e) {
            MyNode selected = getSelectedNode();
            e.getPresentation().setEnabled(selected != null && selected.getConfigurable() instanceof LspServerNamedConfigurable);
        }
    }

    private @Nullable MyNode getSelectedNode() {
        TreePath selectionPath = myTree.getSelectionPath();
        if (selectionPath != null && selectionPath.getLastPathComponent() instanceof MyNode node) {
            return node;
        }
        return null;
    }

    @RequiredUIAccess
    @Override
    public void initUi() {
        addServerNodes();
        super.initUi();
    }

    @RequiredUIAccess
    private void addServerNodes() {
        for (LspServerConfiguration server : mySettings.getServers()) {
            addServerNode(server.copy());
        }
        for (LspIntegrationSettingsProvider provider : LspIntegrationSettingsProvider.EP_NAME.getExtensionList()) {
            PluginDescriptor pluginDescriptor = PluginManager.getPlugin(provider.getClass());
            if (pluginDescriptor == null) {
                continue;
            }
            addPluginServerNode(provider, mySettings.getPluginConfiguration(provider), pluginDescriptor);
        }
    }

    private String generateUniqueName() {
        String baseName = LspUiLocalize.lspSettingsDefaultName().get();
        Set<String> existingNames = new LinkedHashSet<>();
        for (LspServerConfiguration server : mySettings.getServers()) {
            existingNames.add(server.getName());
        }
        int counter = 1;
        String name = baseName;
        while (existingNames.contains(name)) {
            name = baseName + " " + counter;
            counter++;
        }
        return name;
    }

    @RequiredUIAccess
    private MyNode addServerNode(LspServerConfiguration config) {
        LspServerNamedConfigurable configurable = new LspServerNamedConfigurable(myProject, config, TREE_UPDATER);
        MyNode node = new MyNode(configurable);
        Disposer.register(getDisposable(), configurable);
        addNode(node, myRoot);
        return node;
    }

    @RequiredUIAccess
    private MyNode addPluginServerNode(LspIntegrationSettingsProvider provider,
                                       LspPluginServerConfiguration configuration,
                                       PluginDescriptor pluginDescriptor) {
        PluginLspServerNamedConfigurable configurable =
            new PluginLspServerNamedConfigurable(myProject, provider, configuration, pluginDescriptor, TREE_UPDATER);
        MyNode node = new MyNode(configurable);
        Disposer.register(getDisposable(), configurable);
        addNode(node, myRoot);
        return node;
    }

    private Disposable getDisposable() {
        if (myDisposable == null) {
            myDisposable = Disposable.newDisposable("LspServersConfigurable");
        }
        return myDisposable;
    }

    @Override
    protected void processRemovedItems() {
    }

    @Override
    protected boolean wasObjectStored(Object editableObject) {
        if (editableObject instanceof LspServerConfiguration) {
            return mySettings.getServers().contains(editableObject);
        }
        return false;
    }

    @RequiredUIAccess
    @Override
    public void apply() throws ConfigurationException {
        super.apply();

        List<LspServerConfiguration> newServers = new ArrayList<>();
        List<PluginLspServerNamedConfigurable> pluginConfigurables = new ArrayList<>();
        processNodes(node -> {
            Object configurable = node.getConfigurable();
            if (configurable instanceof LspServerNamedConfigurable serverConfigurable) {
                newServers.add(serverConfigurable.getEditableObject());
            }
            else if (configurable instanceof PluginLspServerNamedConfigurable pluginConfigurable) {
                pluginConfigurables.add(pluginConfigurable);
            }
            return true;
        });

        Map<String, Integer> nameCounts = new LinkedHashMap<>();
        for (LspServerConfiguration server : newServers) {
            nameCounts.merge(server.getName(), 1, Integer::sum);
        }
        List<String> duplicateNames = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : nameCounts.entrySet()) {
            if (entry.getValue() > 1) {
                duplicateNames.add(entry.getKey());
            }
        }
        if (!duplicateNames.isEmpty()) {
            throw new ConfigurationException(LspUiLocalize.lspSettingsErrorDuplicateNames(String.join(", ", duplicateNames)));
        }

        Set<Class<? extends LspIntegrationProvider>> providersToRestart = new LinkedHashSet<>();
        for (PluginLspServerNamedConfigurable configurable : pluginConfigurables) {
            Class<? extends LspIntegrationProvider> providerClass = configurable.applyConfiguration();
            if (providerClass != null) {
                providersToRestart.add(providerClass);
            }
        }

        mySettings.getServers().clear();
        mySettings.getServers().addAll(newServers);
        if (!myProject.isDefault()) {
            LspClientManager.getInstance(myProject).stopAndRestartClientsIfNeeded(ConfigurableLspIntegrationProvider.class);
            for (Class<? extends LspIntegrationProvider> providerClass : providersToRestart) {
                LspClientManager.getInstance(myProject).stopAndRestartClientsIfNeeded(providerClass);
            }
            EditorNotifications.getInstance(myProject).updateAllNotifications();
        }
    }

    @RequiredUIAccess
    @Override
    public boolean isModified() {
        if (super.isModified()) {
            return true;
        }

        List<LspServerConfiguration> currentConfigs = new ArrayList<>();
        processNodes(node -> {
            if (node.getConfigurable() instanceof LspServerNamedConfigurable configurable) {
                currentConfigs.add(configurable.getEditableObject());
            }
            return true;
        });

        List<LspServerConfiguration> servers = mySettings.getServers();
        if (currentConfigs.size() != servers.size()) {
            return true;
        }

        for (int i = 0; i < currentConfigs.size(); i++) {
            if (!currentConfigs.get(i).equals(servers.get(i))) {
                return true;
            }
        }
        return false;
    }

    @RequiredUIAccess
    @Override
    public void reset() {
        myRoot.removeAllChildren();
        addServerNodes();

        super.reset();
    }

    @RequiredUIAccess
    @Override
    public void disposeUIResources() {
        super.disposeUIResources();
        if (myDisposable != null) {
            Disposer.dispose(myDisposable);
            myDisposable = null;
        }
    }

    private void processNodes(Predicate<MyNode> processor) {
        TreeNode root = (TreeNode) myTree.getModel().getRoot();
        for (int i = 0; i < root.getChildCount(); i++) {
            MyNode node = (MyNode) root.getChildAt(i);
            if (!processor.test(node)) {
                break;
            }
        }
    }

    private static class LspServerNamedConfigurable extends NamedConfigurable<LspServerConfiguration> implements Disposable {
        private final Project myProject;
        private final LspServerConfiguration myServerConfiguration;
        private final Runnable myUpdateTree;

        private @Nullable LspServerConfigurable myServerConfigurable;

        @RequiredUIAccess
        private LspServerNamedConfigurable(Project project, LspServerConfiguration serverConfiguration, Runnable updateTree) {
            myProject = project;
            myServerConfiguration = serverConfiguration;
            myUpdateTree = updateTree;
        }

        @Override
        public void setDisplayName(String name) {
            myServerConfiguration.setName(name);
        }

        @Override
        public LspServerConfiguration getEditableObject() {
            return myServerConfiguration;
        }

        @Override
        public LocalizeValue getBannerSlogan() {
            return LocalizeValue.of(myServerConfiguration.getName());
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
            return myServerConfiguration.getName().isEmpty()
                ? LspUiLocalize.lspSettingsDefaultName()
                : LocalizeValue.of(myServerConfiguration.getName());
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

        @RequiredUIAccess
        @Override
        public void dispose() {
            if (myServerConfigurable != null) {
                myServerConfigurable.disposeUIResources();
            }
            myServerConfigurable = null;
        }

        private LspServerConfigurable getServerConfigurable() {
            if (myServerConfigurable == null) {
                myServerConfigurable = new LspServerConfigurable(myProject, myServerConfiguration);
            }
            return myServerConfigurable;
        }
    }
}

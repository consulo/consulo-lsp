// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.lsWidget;

import com.intellij.platform.lang.localize.LangLocalize;
import com.intellij.platform.lang.lsWidget.LanguageServiceItemRunningState;
import com.intellij.platform.lang.lsWidget.LanguageServicePopupSection;
import com.intellij.platform.lang.lsWidget.LanguageServiceWidgetItem;
import com.intellij.platform.lang.lsWidget.OpenSettingsAction;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspClientManager;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.configurable.Configurable;
import consulo.localize.LocalizeValue;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnAction;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.ui.image.Image;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerInfo;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@code icon} is used in the Language Services popup and also in the status bar,
 * but in the latter case, the Platform will colorize the icon to be status-bar-friendly in the current UI theme.
 * Implementations may override {@link #getStatusBarIcon()} if they want to provide different icons for the status bar and for the popup item.
 * <p>
 * The default icon is {@code PlatformIconGroup.webreferencesServer()}.
 */
public class LspClientWidgetItem extends LanguageServiceWidgetItem {
    protected final LspClient lspClient;
    private final @Nullable VirtualFile myCurrentFile;
    private final Image myIcon;
    private final @Nullable Class<? extends Configurable> mySettingsPageClass;

    private final Image myStatusBarIcon;
    private final boolean myError;
    private final LanguageServiceItemRunningState myRunningState;
    private @Nullable LanguageServicePopupSection myWidgetActionLocation;

    public LspClientWidgetItem(LspClient lspClient, @Nullable VirtualFile currentFile) {
        this(lspClient, currentFile, PlatformIconGroup.webreferencesServer());
    }

    public LspClientWidgetItem(LspClient lspClient, @Nullable VirtualFile currentFile, Image icon) {
        this(lspClient, currentFile, icon, null);
    }

    public LspClientWidgetItem(
        LspClient lspClient,
        @Nullable VirtualFile currentFile,
        Image icon,
        @Nullable Class<? extends Configurable> settingsPageClass
    ) {
        this.lspClient = lspClient;
        myCurrentFile = currentFile;
        myIcon = icon;
        mySettingsPageClass = settingsPageClass;

        myStatusBarIcon = icon;
        myError = lspClient.getState() == LspServerState.ShutdownUnexpectedly;
        myRunningState = switch (lspClient.getState()) {
            case Running -> LanguageServiceItemRunningState.Running;
            case Initializing -> LanguageServiceItemRunningState.Initializing;
            default -> LanguageServiceItemRunningState.NotRunning;
        };
    }

    @Override
    public Image getStatusBarIcon() {
        return myStatusBarIcon;
    }

    @Override
    public LocalizeValue getStatusBarTooltip() {
        return LocalizeValue.of(lspClient.getDescriptor().getPresentableName() + getVersionPostfix());
    }

    @Override
    public boolean isError() {
        return myError;
    }

    @Override
    public LanguageServiceItemRunningState getRunningState() {
        return myRunningState;
    }

    @Override
    public LanguageServicePopupSection getWidgetActionLocation() {
        LanguageServicePopupSection location = myWidgetActionLocation;
        if (location == null) {
            if (myCurrentFile != null &&
                lspClient.getDescriptor().isSupportedFile(myCurrentFile) &&
                isUnderAnyRoot(myCurrentFile)) {
                location = LanguageServicePopupSection.ForCurrentFile;
            }
            else {
                location = LanguageServicePopupSection.Other;
            }
            myWidgetActionLocation = location;
        }
        return location;
    }

    private boolean isUnderAnyRoot(VirtualFile file) {
        for (VirtualFile root : lspClient.getDescriptor().getRoots()) {
            if (VirtualFileUtil.isAncestor(root, file, true)) {
                return true;
            }
        }
        return false;
    }

    protected LocalizeValue getWidgetActionText() {
        return switch (lspClient.getState()) {
            case Initializing -> LangLocalize.languageServicesWidgetItemInitializing(getItemLabel());
            case Running -> LocalizeValue.of(getItemLabel());
            case ShutdownNormally -> LangLocalize.languageServicesWidgetItemShutdownNormally(getItemLabel());
            case ShutdownUnexpectedly -> LangLocalize.languageServicesWidgetItemShutdownUnexpectedly(getItemLabel());
        };
    }

    @SuppressWarnings("deprecation")
    protected String getItemLabel() {
        return getServerLabel();
    }

    /**
     * @deprecated Renamed to {@link #getItemLabel()}
     */
    @Deprecated
    protected String getServerLabel() {
        return lspClient.getDescriptor().getPresentableName() + getVersionPostfix() + getRootPostfix();
    }

    protected String getVersionPostfix() {
        // Maybe try shortening long version strings automatically? Example `1.36.4 (release, aarch64-apple-darwin)` -> `1.36.4…`
        InitializeResult initializeResult = lspClient.getInitializeResult();
        ServerInfo serverInfo = initializeResult != null ? initializeResult.getServerInfo() : null;
        String version = serverInfo != null ? serverInfo.getVersion() : null;
        return version != null ? " " + version : "";
    }

    protected String getRootPostfix() {
        VirtualFile[] roots = lspClient.getDescriptor().getRoots();
        Collection<LspClient> lspClients = LspClientManager.getInstance(lspClient.getProject()).getClients(lspClient.getProviderClass());
        return lspClients.size() >= 2 && roots.length == 1 ? " …/" + roots[0].getName() : "";
    }

    @Override
    protected AnAction createWidgetMainAction() {
        if (mySettingsPageClass != null) {
            return new OpenSettingsAction(mySettingsPageClass, getWidgetActionText(), myIcon);
        }
        return new AnAction(getWidgetActionText(), LocalizeValue.empty(), myIcon) {
            @Override
            @RequiredUIAccess
            public void actionPerformed(AnActionEvent e) {
                // There's no single reasonable action that would work for each LSP API-based plugin.
                // The plugins are strongly recommended to override `LspIntegrationProvider.createWidgetItem()`.
                // Typical implementation:
                //     public LspClientWidgetItem createWidgetItem(...) { return new LspClientWidgetItem(lspClient, currentFile, fooIcon, FooConfigurable.class); }
            }
        };
    }

    @Override
    protected List<AnAction> createWidgetInlineActions() {
        List<AnAction> actions = new ArrayList<>();

        actions.addAll(createAdditionalInlineActions());
        AnAction stopOrRestartAction = createStopOrRestartAction();
        if (stopOrRestartAction != null) {
            actions.add(stopOrRestartAction);
        }
        if (mySettingsPageClass != null) {
            actions.add(new OpenSettingsAction(mySettingsPageClass));
        }

        return actions;
    }

    protected @Nullable AnAction createStopOrRestartAction() {
        if (getWidgetActionLocation() == LanguageServicePopupSection.ForCurrentFile) {
            return new RestartLspClientAction(lspClient);
        }
        else {
            return switch (lspClient.getState()) {
                case Initializing, Running -> new StopLspClientAction(lspClient);
                case ShutdownNormally -> null;
                case ShutdownUnexpectedly -> new RestartLspClientAction(lspClient);
            };
        }
    }

    protected List<AnAction> createAdditionalInlineActions() {
        if (lspClient.getState() != LspServerState.ShutdownUnexpectedly) {
            return List.of();
        }
        AnAction stderrAction = LspWidgetInternalService.getInstance().createShowErrorOutputAction(lspClient);
        return stderrAction != null ? List.of(stderrAction) : List.of();
    }

    private static class RestartLspClientAction extends DumbAwareAction {
        private final LspClient myLspClient;

        RestartLspClientAction(LspClient lspClient) {
            super(LspLocalize.actionRestartlspserveractionText(), LocalizeValue.empty(), PlatformIconGroup.actionsRestart());
            myLspClient = lspClient;
        }

        @Override
        @RequiredUIAccess
        public void actionPerformed(AnActionEvent e) {
            LspWidgetInternalService.getInstance().restartLspClient(myLspClient);
        }
    }

    private static class StopLspClientAction extends DumbAwareAction {
        private final LspClient myLspClient;

        StopLspClientAction(LspClient lspClient) {
            super(LspLocalize.actionStoplspserveractionText(), LocalizeValue.empty(), PlatformIconGroup.actionsRestart());
            myLspClient = lspClient;
        }

        @Override
        @RequiredUIAccess
        public void actionPerformed(AnActionEvent e) {
            LspWidgetInternalService.getInstance().stopLspClient(myLspClient);
        }
    }
}

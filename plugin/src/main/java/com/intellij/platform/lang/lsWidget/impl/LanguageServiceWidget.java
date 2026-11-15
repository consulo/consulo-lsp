// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lang.lsWidget.impl;

import com.intellij.platform.lang.localize.LangLocalize;
import com.intellij.platform.lang.lsWidget.LanguageServiceItemRunningState;
import com.intellij.platform.lang.lsWidget.LanguageServicePopupSection;
import com.intellij.platform.lang.lsWidget.LanguageServiceWidgetItem;
import com.intellij.platform.lang.lsWidget.LanguageServiceWidgetItemsProvider;
import consulo.dataContext.DataContext;
import consulo.externalService.plugin.PluginsConfigurable;
import consulo.fileEditor.statusBar.EditorBasedStatusBarPopup;
import consulo.ide.setting.ShowSettingsUtil;
import consulo.localize.LocalizeValue;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.Project;
import consulo.project.ui.wm.StatusBar;
import consulo.project.ui.wm.StatusBarWidget;
import consulo.project.ui.wm.StatusBarWidgetFactory;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.BadgeIconSupplier;
import consulo.ui.ex.action.ActionGroup;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.AnActionWithSyncUpdate;
import consulo.ui.ex.action.AnSeparator;
import consulo.ui.ex.action.DefaultActionGroup;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.ui.ex.awt.JBUI;
import consulo.ui.ex.popup.JBPopupFactory;
import consulo.ui.ex.popup.ListPopup;
import consulo.ui.image.Image;
import consulo.ui.image.ImageEffects;
import consulo.ui.style.ComponentColors;
import consulo.ui.style.StyleManager;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Consulo: {@code EditorBasedStatusBarPopup} of Consulo shows the widget by its presentation (icon and text), there is no
 * {@code createComponent()}, so the upstream {@code LanguageServiceTextPanel} (a fixed accessible name) is not ported.
 */
public class LanguageServiceWidget extends EditorBasedStatusBarPopup {
    private static final int maxIconsInStatusBar = 4;

    /**
     * This cache helps to perform some calculations in BGT (in {@link #getWidgetState})
     * and then to use the cached result in EDT (in {@link #createActionGroup}).
     * Specifically, implementing {@link LanguageServiceWidgetItem#getWidgetActionLocation()} lazily
     * helps to avoid running costly calculations in EDT.
     */
    private volatile List<LanguageServiceWidgetItem> myCachedWidgetItems = List.of();

    public LanguageServiceWidget(Project project, StatusBarWidgetFactory factory) {
        super(project, factory, false);
    }

    @Override
    protected StatusBarWidget createInstance(Project project) {
        return new LanguageServiceWidget(project, myFactory);
    }

    /**
     * Registers the item updaters here, not in the constructor hook {@code registerCustomListeners}: a provider may call {@code update}
     * from another thread right away, and before construction ends the update flow of the superclass is still null.
     * <p>
     * Consulo: the widget itself is the parent disposable (upstream: the message bus connection of the widget),
     * and the updaters are registered after {@code super.install}, when the widget knows its status bar.
     */
    @Override
    public void install(StatusBar statusBar) {
        super.install(statusBar);
        for (LanguageServiceWidgetItemsProvider provider : LanguageServiceWidgetItemsProvider.EP_NAME.getExtensionList()) {
            provider.registerWidgetUpdaters(getProject(), this, this::update);
        }
    }

    @Override
    protected WidgetState getWidgetState(@Nullable VirtualFile file) {
        // If there are more than maxIconsInStatusBar services, then not all icons show up in the status bar.
        // Sorting helps to make sure that icons with an error marker go first.
        List<LanguageServiceWidgetItem> allItems = new ArrayList<>();
        for (LanguageServiceWidgetItemsProvider provider : LanguageServiceWidgetItemsProvider.EP_NAME.getExtensionList()) {
            allItems.addAll(provider.createWidgetItems(getProject(), file));
        }
        allItems.sort(Comparator.comparing(LanguageServiceWidgetItem::isError).reversed());
        myCachedWidgetItems = allItems;
        if (allItems.isEmpty()) {
            return WidgetState.HIDDEN;
        }

        List<LanguageServiceWidgetItem> fileSpecificItems = file != null
            ? allItems.stream().filter(it -> it.getWidgetActionLocation() == LanguageServicePopupSection.ForCurrentFile).toList()
            : List.of();

        Image widgetIcon = addLivenessBadge(
            !fileSpecificItems.isEmpty() ? createStatusBarIcon(fileSpecificItems) : PlatformIconGroup.webreferencesServer(),
            !fileSpecificItems.isEmpty() ? fileSpecificItems : allItems
        );

        LocalizeValue widgetText = fileSpecificItems.size() > maxIconsInStatusBar
            ? LocalizeValue.of("+" + (fileSpecificItems.size() - maxIconsInStatusBar + 1))
            : LocalizeValue.empty();

        LocalizeValue tooltip = fileSpecificItems.isEmpty()
            ? LangLocalize.languageServicesWidget()
            : LangLocalize.languageServicesWidgetTooltipRunningOnCurrentFileList(
                fileSpecificItems.stream().map(it -> "- " + it.getStatusBarTooltip().get()).collect(Collectors.joining("<br>"))
            );

        WidgetState state = new WidgetState(tooltip, widgetText, true);
        state.setIcon(widgetIcon);
        return state;
    }

    /**
     * The current file's items decide the badge when there are any; a not-yet-ready service wins over a running one.
     */
    private static Image addLivenessBadge(Image icon, List<LanguageServiceWidgetItem> items) {
        if (items.stream().anyMatch(it -> it.getRunningState() == LanguageServiceItemRunningState.Initializing)) {
            return BadgeIconSupplier.withIconBadge(icon, ComponentColors.ICON_BADGE_WARNING);
        }
        if (items.stream().anyMatch(it -> it.getRunningState() == LanguageServiceItemRunningState.Running)) {
            return BadgeIconSupplier.withIconBadge(icon, ComponentColors.ICON_BADGE_SUCCESS);
        }
        return icon;
    }

    private static Image createStatusBarIcon(List<LanguageServiceWidgetItem> widgetItems) {
        // either up to maxIconsInStatusBar icons or maxIconsInStatusBar - 1 icons and "+N" text
        List<LanguageServiceWidgetItem> items =
            widgetItems.size() <= maxIconsInStatusBar ? widgetItems : widgetItems.subList(0, maxIconsInStatusBar - 1);
        if (items.size() == 1) {
            return getStatusBarFriendlyIcon(items.get(0));
        }

        // Consulo: no `RowIcon`, the icons are appended with `ImageEffects.appendRight`
        Image separatorIcon = ImageEffects.empty(3, 16);
        Image result = getStatusBarFriendlyIcon(items.get(0));
        for (LanguageServiceWidgetItem item : items.subList(1, items.size())) {
            result = ImageEffects.appendRight(ImageEffects.appendRight(result, separatorIcon), getStatusBarFriendlyIcon(item));
        }
        return result;
    }

    private static Image getStatusBarFriendlyIcon(LanguageServiceWidgetItem item) {
        // Consulo: no `JBUI.CurrentTheme.StatusBar.Widget.FOREGROUND`; the text color, and the dimmer info color in light themes
        //  (upstream: `FOREGROUND.brighter()`, without it icons are too noisy in light themes)
        ComponentColors statusBarFriendlyColor = StyleManager.get().getCurrentStyle().isDark()
            ? ComponentColors.TEXT_FOREGROUND
            : ComponentColors.INFO_FOREGROUND;
        Image statusBarFriendlyIcon = ImageEffects.colorize(item.getStatusBarIcon(), statusBarFriendlyColor);
        return item.isError()
            ? ImageEffects.layered(statusBarFriendlyIcon, PlatformIconGroup.nodesErrormark())
            : statusBarFriendlyIcon;
    }

    @Override
    @RequiredUIAccess
    protected ListPopup createPopup(DataContext context) {
        ListPopup popup = JBPopupFactory.getInstance().createActionGroupPopup(
            LangLocalize.languageServicesWidget().get(),
            createActionGroup(),
            context,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true
        );
        popup.setMinimumSize(JBUI.size(300, 1));
        return popup;
    }

    private ActionGroup createActionGroup() {
        List<LanguageServiceWidgetItem> allItems = myCachedWidgetItems;
        List<LanguageServiceWidgetItem> fileSpecificItems =
            allItems.stream().filter(it -> it.getWidgetActionLocation() == LanguageServicePopupSection.ForCurrentFile).toList();
        List<LanguageServiceWidgetItem> otherItems = allItems.stream().filter(it -> !fileSpecificItems.contains(it)).toList();

        DefaultActionGroup group = new DefaultActionGroup();

        group.add(AnSeparator.create(LangLocalize.languageServicesWidgetSectionRunningOnCurrentFile()));
        if (!fileSpecificItems.isEmpty()) {
            for (LanguageServiceWidgetItem item : fileSpecificItems) {
                group.add(item.createWidgetAction());
            }
        }
        else {
            group.add(NoServices.INSTANCE);
        }

        group.add(AnSeparator.create(LangLocalize.languageServicesWidgetSectionRunningOnOtherFiles()));
        for (LanguageServiceWidgetItem item : otherItems) {
            group.add(item.createWidgetAction());
        }
        group.addSeparator();
        group.add(new MoreLanguagesAction());

        return group;
    }

    /**
     * Consulo: the plugin settings have no Marketplace search by tag (upstream: {@code navigateToMarketplace("/tag:\"Language Server\"")}),
     * the action opens the plugins page.
     */
    private class MoreLanguagesAction extends DumbAwareAction {
        MoreLanguagesAction() {
            super(LangLocalize.languageServicesWidgetMoreLanguages());
        }

        @Override
        @RequiredUIAccess
        public void actionPerformed(AnActionEvent e) {
            ShowSettingsUtil.getInstance().showAndSelect(getProject(), PluginsConfigurable.class);
        }
    }

    private static final class NoServices extends DumbAwareAction implements AnActionWithSyncUpdate {
        static final NoServices INSTANCE = new NoServices();

        private NoServices() {
            super(LangLocalize.languageServicesWidgetNoServices());
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setEnabled(false);
        }

        @Override
        @RequiredUIAccess
        public void actionPerformed(AnActionEvent e) {
        }
    }
}

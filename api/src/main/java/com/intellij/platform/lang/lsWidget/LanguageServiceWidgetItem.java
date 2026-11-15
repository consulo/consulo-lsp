// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lang.lsWidget;

import consulo.platform.base.icon.PlatformIconGroup;
import consulo.ui.ex.BadgeIconSupplier;
import consulo.ui.ex.action.AnAction;
import consulo.ui.ex.action.util.ActionUtil;
import consulo.ui.image.Image;
import consulo.ui.image.ImageEffects;
import consulo.ui.style.ComponentColors;
import consulo.localize.LocalizeValue;

import java.util.List;

public abstract class LanguageServiceWidgetItem {
    /**
     * An icon to show in the status bar (size: 16x16).
     * The Platform will colorize the icon to be status-bar-friendly in the current UI theme.
     * The Platform will add an error mark to the icon if {@link #isError()} is {@code true}.
     */
    public abstract Image getStatusBarIcon();

    /**
     * A tooltip for the status bar widget icon.
     */
    public abstract LocalizeValue getStatusBarTooltip();

    /**
     * If {@code true} then the Platform will add the error mark to the icon in the status bar,
     * and to the action returned by the {@link #createWidgetMainAction} function.
     */
    public boolean isError() {
        return false;
    }

    /**
     * If not {@link LanguageServiceItemRunningState#NotRunning} and {@link #isError()} is {@code false},
     * then the Platform will add a liveness badge (green for {@link LanguageServiceItemRunningState#Running},
     * amber for {@link LanguageServiceItemRunningState#Initializing})
     * to the icon of the action returned by the {@link #createWidgetMainAction} function.
     */
    public LanguageServiceItemRunningState getRunningState() {
        return LanguageServiceItemRunningState.NotRunning;
    }

    public abstract LanguageServicePopupSection getWidgetActionLocation();

    public final AnAction createWidgetAction() {
        AnAction mainAction = createWidgetMainAction();
        Image icon = mainAction.getTemplatePresentation().getIcon();
        if (icon != null) {
            Image newIcon;
            if (isError()) {
                newIcon = ImageEffects.layered(icon, PlatformIconGroup.nodesErrormark());
            }
            else if (getRunningState() == LanguageServiceItemRunningState.Running) {
                newIcon = BadgeIconSupplier.withIconBadge(icon, ComponentColors.ICON_BADGE_SUCCESS);
            }
            else if (getRunningState() == LanguageServiceItemRunningState.Initializing) {
                newIcon = BadgeIconSupplier.withIconBadge(icon, ComponentColors.ICON_BADGE_WARNING);
            }
            else {
                newIcon = icon;
            }
            mainAction.getTemplatePresentation().setIcon(newIcon);
        }

        // The action popups show the inline actions next to the main action.
        mainAction.getTemplatePresentation().putClientProperty(ActionUtil.INLINE_ACTIONS, createWidgetInlineActions());
        return mainAction;
    }

    protected abstract AnAction createWidgetMainAction();

    protected List<AnAction> createWidgetInlineActions() {
        return List.of();
    }
}

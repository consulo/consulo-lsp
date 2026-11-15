// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lang.lsWidget;

import com.intellij.platform.lang.localize.LangLocalize;
import com.intellij.platform.lang.lsWidget.internal.LanguageServiceWidgetActionsService;
import consulo.configurable.Configurable;
import consulo.localize.LocalizeValue;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.ui.image.Image;

/**
 * - When creating an action for {@link LanguageServiceWidgetItem#createWidgetMainAction}, pass item-specific {@code text} and {@code icon}
 * - When creating an action for {@link LanguageServiceWidgetItem#createWidgetInlineActions}, pass only {@code settingsPageClass}
 */
public class OpenSettingsAction extends DumbAwareAction {
    private final Class<? extends Configurable> mySettingsPageClass;

    public OpenSettingsAction(Class<? extends Configurable> settingsPageClass) {
        this(settingsPageClass, LangLocalize.languageServicesWidgetOpenSettingsAction());
    }

    public OpenSettingsAction(Class<? extends Configurable> settingsPageClass, LocalizeValue text) {
        this(settingsPageClass, text, PlatformIconGroup.generalSettings());
    }

    public OpenSettingsAction(Class<? extends Configurable> settingsPageClass, LocalizeValue text, Image icon) {
        super(text, LocalizeValue.empty(), icon);
        mySettingsPageClass = settingsPageClass;
    }

    @Override
    @RequiredUIAccess
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getData(Project.KEY);
        if (project == null) {
            return;
        }
        LanguageServiceWidgetActionsService.getInstance().openWidgetItemRelatedSettings(project, mySettingsPageClass);
    }
}

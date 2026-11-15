// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lang.lsWidget.impl;

import com.intellij.platform.lang.lsWidget.internal.LanguageServiceWidgetActionsService;
import consulo.annotation.component.ServiceImpl;
import consulo.configurable.Configurable;
import consulo.ide.setting.ShowSettingsUtil;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The implementation of {@link LanguageServiceWidgetActionsService}.
 */
@ServiceImpl
@Singleton
public class LanguageServiceWidgetActionsServiceImpl extends LanguageServiceWidgetActionsService {
    private final ShowSettingsUtil myShowSettingsUtil;

    @Inject
    public LanguageServiceWidgetActionsServiceImpl(ShowSettingsUtil showSettingsUtil) {
        myShowSettingsUtil = showSettingsUtil;
    }

    @Override
    @RequiredUIAccess
    public void openWidgetItemRelatedSettings(Project project, Class<? extends Configurable> settingsPageClass) {
        myShowSettingsUtil.showAndSelect(project, settingsPageClass);
    }
}

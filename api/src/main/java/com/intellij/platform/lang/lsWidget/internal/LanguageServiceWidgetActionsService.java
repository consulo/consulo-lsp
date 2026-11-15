// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lang.lsWidget.internal;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.application.Application;
import consulo.configurable.Configurable;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;

/**
 * The service is declared in the {@code consulo.lsp.api} module, and the implementation
 * ({@code com.intellij.platform.lang.lsWidget.impl.LanguageServiceWidgetActionsServiceImpl}) lives in the {@code consulo.lsp} plugin.
 */
@ServiceAPI(ComponentScope.APPLICATION)
public abstract class LanguageServiceWidgetActionsService {

    @RequiredUIAccess
    public abstract void openWidgetItemRelatedSettings(Project project, Class<? extends Configurable> settingsPageClass);

    public static LanguageServiceWidgetActionsService getInstance() {
        return Application.get().getInstance(LanguageServiceWidgetActionsService.class);
    }
}

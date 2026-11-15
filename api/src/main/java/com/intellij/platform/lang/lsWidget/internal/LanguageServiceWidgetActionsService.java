// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lang.lsWidget.internal;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.application.Application;
import consulo.configurable.Configurable;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;

/**
 * This class is located in the `intellij.platform.lang` module,
 * so it doesn't have access to FUS-related classes like
 * {@code com.intellij.platform.lang.lsWidget.impl.fus.LanguageServiceWidgetUsagesCollector}.
 * The interface implementation ({@code com.intellij.platform.lang.lsWidget.impl.LanguageServiceWidgetActionsServiceImpl})
 * is located in the `intellij.platform.lang.impl` module, so it is able to log data to the FUS.
 * <p>
 * Consulo: the service is in the {@code consulo.lsp.api} module, the implementation in the {@code consulo.lsp} plugin;
 * Consulo has no FUS, the usages collector is not ported.
 */
@ServiceAPI(ComponentScope.APPLICATION)
public abstract class LanguageServiceWidgetActionsService {

    @RequiredUIAccess
    public abstract void openWidgetItemRelatedSettings(Project project, Class<? extends Configurable> settingsPageClass);

    public static LanguageServiceWidgetActionsService getInstance() {
        return Application.get().getInstance(LanguageServiceWidgetActionsService.class);
    }
}

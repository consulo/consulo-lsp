// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lang.lsWidget.impl;

import com.intellij.platform.lang.localize.LangLocalize;
import consulo.annotation.component.ExtensionImpl;
import consulo.fileEditor.statusBar.StatusBarEditorBasedWidgetFactory;
import consulo.project.Project;
import consulo.project.ui.wm.StatusBarWidget;

@ExtensionImpl(id = LanguageServiceWidgetFactory.LANGUAGE_SERVICES_WIDGET_ID, order = "after positionWidget, before lineSeparatorWidget")
public class LanguageServiceWidgetFactory extends StatusBarEditorBasedWidgetFactory {
    public static final String LANGUAGE_SERVICES_WIDGET_ID = "LanguageServiceStatusBarWidget";

    @Override
    public String getId() {
        return LANGUAGE_SERVICES_WIDGET_ID;
    }

    @Override
    public String getDisplayName() {
        return LangLocalize.languageServicesWidget().get();
    }

    @Override
    public StatusBarWidget createWidget(Project project) {
        return new LanguageServiceWidget(project, this);
    }
}

// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lang.lsWidget;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ExtensionAPI;
import consulo.component.extension.ExtensionPointName;
import consulo.disposer.Disposable;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

import java.util.List;

@ExtensionAPI(ComponentScope.APPLICATION)
public abstract class LanguageServiceWidgetItemsProvider {
    public static final ExtensionPointName<LanguageServiceWidgetItemsProvider> EP_NAME =
        ExtensionPointName.create(LanguageServiceWidgetItemsProvider.class);

    public abstract List<LanguageServiceWidgetItem> createWidgetItems(Project project, @Nullable VirtualFile currentFile);

    /**
     * {@link LanguageServiceWidgetItemsProvider} implementations should ask the Platform to update the 'Language Services' widget
     * when the state of some language service changes.
     * Typically, they register some technology-specific service state listener,
     * and call {@code updateWidget} function on service state change.
     * Once the {@code updateWidget} function is called, the Platform rebuilds the widget from scratch,
     * which means that it collects the up-to-date information by calling {@link #createWidgetItems}.
     * <p>
     * Make sure to use the {@code widgetDisposable} to unregister technology-specific listeners, otherwise they will leak.
     * <p>
     * Implementation example:
     * <pre>
     *    FooManager.getInstance(project).addServiceStateListener(
     *      new ServiceStateListener() {
     *        public void serviceStateChanged() { updateWidget.run(); }
     *      },
     *      widgetDisposable
     *    );
     * </pre>
     */
    public void registerWidgetUpdaters(Project project, Disposable widgetDisposable, Runnable updateWidget) {
    }
}

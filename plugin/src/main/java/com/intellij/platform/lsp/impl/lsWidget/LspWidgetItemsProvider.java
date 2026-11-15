package com.intellij.platform.lsp.impl.lsWidget;

import com.intellij.platform.lang.lsWidget.LanguageServiceWidgetItem;
import com.intellij.platform.lang.lsWidget.LanguageServiceWidgetItemsProvider;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspClientManager;
import com.intellij.platform.lsp.api.LspClientManagerListener;
import com.intellij.platform.lsp.api.LspIntegrationProvider;
import consulo.annotation.component.ExtensionImpl;
import consulo.disposer.Disposable;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@ExtensionImpl(id = "LspWidgetItemsProvider")
public class LspWidgetItemsProvider extends LanguageServiceWidgetItemsProvider {

    @Override
    public List<LanguageServiceWidgetItem> createWidgetItems(Project project, @Nullable VirtualFile currentFile) {
        List<LanguageServiceWidgetItem> result = new ArrayList<>();
        for (LspIntegrationProvider provider : LspIntegrationProvider.getAllExtensions()) {
            result.addAll(provider.createWidgetItems(project, currentFile));
        }
        return result;
    }

    @Override
    public void registerWidgetUpdaters(Project project, Disposable widgetDisposable, Runnable updateWidget) {
        LspClientManager.getInstance(project).addListener(
            new LspClientManagerListener() {
                @Override
                public void serverStateChanged(LspClient lspClient) {
                    updateWidget.run();
                }
            },
            widgetDisposable,
            false
        );
    }
}

package com.intellij.platform.lsp.impl.platformListeners;

import com.intellij.platform.lsp.api.LspIntegrationProvider;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.dumb.DumbAware;
import consulo.project.Project;
import consulo.project.startup.PostStartupActivity;
import consulo.ui.UIAccess;

/**
 * Editors restored on project reopen fire {@link LspFileEditorManagerListener#fileOpened fileOpened} before the project file index
 * knows the content roots, so those events start no LSP servers. Re-process the open editors once the project is fully loaded.
 */
@ExtensionImpl
public class LspStartupActivity implements PostStartupActivity, DumbAware {
    @Override
    public void runActivity(Project project, UIAccess uiAccess) {
        if (!LspIntegrationProvider.hasAnyExtensions()) {
            return;
        }
        LspClientManagerImpl.getInstanceImpl(project).onProjectRootsChanged();
    }
}

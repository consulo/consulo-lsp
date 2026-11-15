package com.intellij.platform.lsp.impl.features.formatter;

import consulo.annotation.component.ExtensionImpl;
import consulo.application.dumb.DumbAware;
import consulo.codeEditor.action.TypedAction;
import consulo.project.Project;
import consulo.project.startup.PostStartupActivity;
import consulo.ui.UIAccess;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Installs {@link LspTypedActionHandler}
 * once per application, when the first project is opened, see the {@link LspTypedActionHandler} Javadoc.
 */
@ExtensionImpl
public class LspTypedActionHandlerInstaller implements PostStartupActivity, DumbAware {
    private static final AtomicBoolean ourInstalled = new AtomicBoolean();

    @Override
    public void runActivity(Project project, UIAccess uiAccess) {
        if (!ourInstalled.compareAndSet(false, true)) {
            return;
        }
        uiAccess.give(() -> {
            TypedAction typedAction = TypedAction.getInstance();
            typedAction.setupHandler(new LspTypedActionHandler(typedAction.getHandler()));
        });
    }
}

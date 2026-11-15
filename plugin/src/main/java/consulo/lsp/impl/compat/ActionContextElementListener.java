package consulo.lsp.impl.compat;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.TopicImpl;
import consulo.dataContext.DataContext;
import consulo.ui.ex.action.ActionManager;
import consulo.ui.ex.action.AnAction;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.event.AnActionListener;

/**
 * Records the id of the action being performed for {@link ActionContextElement#getActionThreadContext()}.
 */
@TopicImpl(ComponentScope.APPLICATION)
public final class ActionContextElementListener implements AnActionListener {
    @Override
    public void beforeActionPerformed(AnAction action, DataContext dataContext, AnActionEvent event) {
        String actionId = ActionManager.getInstance().getId(action);
        if (actionId != null) {
            ActionContextElement.push(actionId);
        }
    }

    @Override
    public void afterActionPerformed(AnAction action, DataContext dataContext, AnActionEvent event) {
        String actionId = ActionManager.getInstance().getId(action);
        if (actionId != null) {
            ActionContextElement.pop(actionId);
        }
    }
}

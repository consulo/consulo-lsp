package consulo.lsp.impl.compat;

import org.jspecify.annotations.Nullable;

/**
 * IntelliJ's {@code com.intellij.openapi.actionSystem.ex.ActionContextElement}, as returned by
 * {@code ActionUtil.getActionThreadContext()}: names the action being performed on the current thread.
 * <p>
 * Consulo has no action thread context. The action id is recorded by {@link ActionContextElementListener}
 * ({@code AnActionListener.beforeActionPerformed}/{@code afterActionPerformed}) for the thread that performs the action
 * (the UI thread). Unlike upstream, the context is not propagated to other threads.
 */
public final class ActionContextElement {
    private static volatile @Nullable ActionContextElement ourCurrent;

    private final String myActionId;
    private final Thread myThread;
    private final @Nullable ActionContextElement myPrevious;

    private ActionContextElement(String actionId, Thread thread, @Nullable ActionContextElement previous) {
        myActionId = actionId;
        myThread = thread;
        myPrevious = previous;
    }

    public String getActionId() {
        return myActionId;
    }

    /**
     * {@code ActionUtil.getActionThreadContext()}: the action performed on the current thread, or {@code null}.
     */
    public static @Nullable ActionContextElement getActionThreadContext() {
        ActionContextElement current = ourCurrent;
        return current != null && current.myThread == Thread.currentThread() ? current : null;
    }

    static void push(String actionId) {
        ourCurrent = new ActionContextElement(actionId, Thread.currentThread(), ourCurrent);
    }

    static void pop(String actionId) {
        ActionContextElement current = ourCurrent;
        if (current != null && current.myActionId.equals(actionId)) {
            ourCurrent = current.myPrevious;
        }
    }
}

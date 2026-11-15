package com.intellij.platform.lsp.impl.serviceView;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspServerNotificationsHandlerImpl;
import com.intellij.platform.lsp.impl.lsWidget.LspWidgetInternalServiceImpl;
import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.execution.service.ServiceViewManager;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationType;
import consulo.ui.annotation.RequiredUIAccess;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Objects;

/**
 * The server log of {@code lspClient}. The server log is the console of the client in the Services tool window.
 * The Services tool window is a plugin. Without it, {@link #show} opens the server error output of the client.
 * <p>
 * The user can stop or restart the client before {@link #show}. Then the console of the client is gone.
 * In that case, {@link #show} opens the server error output that the old client keeps.
 * When the old client has no server error output, {@link #show} selects the console of the new client.
 * When there is no new client either, the status bar tells the user that the server log is not available.
 * <p>
 * The Services tool window is a part of the platform, so the console is always available.
 * The 'not available' text is shown as an information notification.
 */
public class LspServerLog {
    private final Project myProject;
    private final String myServerId;
    private final String myPresentableName;

    // The Notifications tool window can keep a notification for a long time. A weak reference lets the old client go.
    private final WeakReference<LspClientImpl> myClientReference;

    public LspServerLog(LspClientImpl lspClient) {
        myProject = lspClient.getProject();
        myServerId = LspClientManagerImpl.getServerId(lspClient);
        myPresentableName = lspClient.getDescriptor().getPresentableName();
        myClientReference = new WeakReference<>(lspClient);
    }

    @RequiredUIAccess
    public void show() {
        if (myProject.isDisposed()) {
            return;
        }

        ServiceViewManager serviceViewManager = myProject.getInstance(ServiceViewManager.class);
        Location location = find(true);
        if (location instanceof Location.Console console) {
            serviceViewManager.select(console.getLspClient(), LspServiceViewContributor.class, true, true);
        }
        else if (location instanceof Location.ErrorOutput errorOutput) {
            LspWidgetInternalServiceImpl.openLspServerErrorOutput(errorOutput.getLspClient());
        }
        else {
            LspServerNotificationsHandlerImpl.SHOW_MESSAGE_GROUP
                .createNotification(LspLocalize.statusBarServerLogNotAvailable(myPresentableName).get(), NotificationType.INFORMATION)
                .notify(myProject);
        }
    }

    /**
     * @param servicesViewAvailable {@code false} when the Services tool window plugin is not installed or is disabled
     */
    public Location find(boolean servicesViewAvailable) {
        List<LspClientImpl> trackedClients = LspClientManagerImpl.getInstanceImpl(myProject).getAllClients();
        LspClientImpl lspClient = myClientReference.get();
        if (lspClient != null && trackedClients.contains(lspClient)) {
            return liveLocation(lspClient, servicesViewAvailable);
        }
        if (lspClient != null && lspClient.getErrorOutput() != null) {
            return new Location.ErrorOutput(lspClient);
        }
        for (LspClientImpl trackedClient : trackedClients) {
            if (LspClientManagerImpl.getServerId(trackedClient).equals(myServerId)) {
                return liveLocation(trackedClient, servicesViewAvailable);
            }
        }
        return Location.NotAvailable.INSTANCE;
    }

    private static Location liveLocation(LspClientImpl lspClient, boolean servicesViewAvailable) {
        return servicesViewAvailable ? new Location.Console(lspClient) : new Location.ErrorOutput(lspClient);
    }

    public sealed interface Location {
        /**
         * The console of {@code lspClient} in the Services tool window.
         */
        final class Console implements Location {
            private final LspClientImpl myLspClient;

            public Console(LspClientImpl lspClient) {
                myLspClient = lspClient;
            }

            public LspClientImpl getLspClient() {
                return myLspClient;
            }

            @Override
            public boolean equals(@Nullable Object o) {
                return o instanceof Console console && myLspClient.equals(console.myLspClient);
            }

            @Override
            public int hashCode() {
                return Objects.hash(myLspClient);
            }

            @Override
            public String toString() {
                return "Console(lspClient=" + myLspClient + ")";
            }
        }

        /**
         * The server error output that {@code lspClient} keeps. A live client can have an empty server error output.
         */
        final class ErrorOutput implements Location {
            private final LspClientImpl myLspClient;

            public ErrorOutput(LspClientImpl lspClient) {
                myLspClient = lspClient;
            }

            public LspClientImpl getLspClient() {
                return myLspClient;
            }

            @Override
            public boolean equals(@Nullable Object o) {
                return o instanceof ErrorOutput errorOutput && myLspClient.equals(errorOutput.myLspClient);
            }

            @Override
            public int hashCode() {
                return Objects.hash(myLspClient);
            }

            @Override
            public String toString() {
                return "ErrorOutput(lspClient=" + myLspClient + ")";
            }
        }

        final class NotAvailable implements Location {
            public static final NotAvailable INSTANCE = new NotAvailable();

            private NotAvailable() {
            }

            @Override
            public String toString() {
                return "NotAvailable";
            }
        }
    }
}

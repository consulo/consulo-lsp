package com.intellij.platform.lsp.impl;

import consulo.annotation.component.ExtensionImpl;
import consulo.project.ui.notification.NotificationGroup;
import consulo.project.ui.notification.NotificationGroupContributor;

import java.util.function.Consumer;

/**
 * Registers the notification groups of the LSP client.
 * Upstream: {@code <notificationGroup id="LSP window/showMessage" displayType="BALLOON" key="notification.group.lsp.message"/>}.
 */
@ExtensionImpl
public class LspNotificationGroupContributor implements NotificationGroupContributor {
    @Override
    public void contribute(Consumer<NotificationGroup> registrator) {
        registrator.accept(LspServerNotificationsHandlerImpl.SHOW_MESSAGE_GROUP);
    }
}

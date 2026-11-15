// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.lsp.ui.frontend;

import com.intellij.lsp.ui.frontend.localize.LspUiLocalize;
import consulo.annotation.component.ExtensionImpl;
import consulo.project.ui.notification.NotificationDisplayType;
import consulo.project.ui.notification.NotificationGroup;
import consulo.project.ui.notification.NotificationGroupContributor;

import java.util.function.Consumer;

/**
 * Registers the notification group of the LSP Servers settings.
 */
@ExtensionImpl
public class LspUiNotificationGroupContributor implements NotificationGroupContributor {
    public static final String NOTIFICATION_GROUP_ID = "Language Servers";

    public static final NotificationGroup NOTIFICATION_GROUP = new NotificationGroup(
        NOTIFICATION_GROUP_ID,
        LspUiLocalize.lspSettingsDisplayName(),
        NotificationDisplayType.BALLOON,
        true
    );

    @Override
    public void contribute(Consumer<NotificationGroup> registrator) {
        registrator.accept(NOTIFICATION_GROUP);
    }
}

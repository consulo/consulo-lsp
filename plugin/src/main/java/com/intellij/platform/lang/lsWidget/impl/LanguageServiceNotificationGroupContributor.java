package com.intellij.platform.lang.lsWidget.impl;

import com.intellij.platform.lang.localize.LangLocalize;
import consulo.annotation.component.ExtensionImpl;
import consulo.project.ui.notification.NotificationDisplayType;
import consulo.project.ui.notification.NotificationGroup;
import consulo.project.ui.notification.NotificationGroupContributor;

import java.util.function.Consumer;

/**
 * Registers the notification group for stopped or restarted language services.
 */
@ExtensionImpl
public class LanguageServiceNotificationGroupContributor implements NotificationGroupContributor {
    public static final NotificationGroup SERVICE_STOPPED_OR_RESTARTED_GROUP = new NotificationGroup(
        "language.service.stopped.or.restarted",
        LangLocalize.languageServicesNotificationGroupServiceStoppedOrRestarted(),
        NotificationDisplayType.BALLOON,
        false
    );

    @Override
    public void contribute(Consumer<NotificationGroup> registrator) {
        registrator.accept(SERVICE_STOPPED_OR_RESTARTED_GROUP);
    }
}

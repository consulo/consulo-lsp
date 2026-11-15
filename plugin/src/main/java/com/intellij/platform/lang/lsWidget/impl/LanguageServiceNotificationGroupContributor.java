package com.intellij.platform.lang.lsWidget.impl;

import com.intellij.platform.lang.localize.LangLocalize;
import consulo.annotation.component.ExtensionImpl;
import consulo.project.ui.notification.NotificationDisplayType;
import consulo.project.ui.notification.NotificationGroup;
import consulo.project.ui.notification.NotificationGroupContributor;

import java.util.function.Consumer;

/**
 * Upstream: {@code <notificationGroup id="language.service.stopped.or.restarted" displayType="BALLOON" isLogByDefault="false"
 * key="language.services.notification.group.service.stopped.or.restarted"/>} in {@code intellij.platform.lang.xml}.
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

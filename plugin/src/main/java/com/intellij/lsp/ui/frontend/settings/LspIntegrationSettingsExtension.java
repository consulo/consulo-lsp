// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.lsp.ui.frontend.settings;

import com.intellij.platform.lsp.api.LspIntegrationSettings;
import com.intellij.platform.lsp.api.LspPluginServerConfiguration;
import consulo.annotation.component.ExtensionImpl;
import jakarta.inject.Inject;

/**
 * Registers {@link LspIntegrationSettingsImpl} as the project {@link LspIntegrationSettings}.
 * {@link LspIntegrationSettings} is a project extension, while the persistent state lives in the
 * {@link LspIntegrationSettingsImpl} project service.
 */
@ExtensionImpl
public final class LspIntegrationSettingsExtension implements LspIntegrationSettings {
    private final LspIntegrationSettingsImpl mySettings;

    @Inject
    public LspIntegrationSettingsExtension(LspIntegrationSettingsImpl settings) {
        mySettings = settings;
    }

    @Override
    public LspPluginServerConfiguration getPluginConfiguration(String serverId) {
        return mySettings.getPluginConfiguration(serverId);
    }
}

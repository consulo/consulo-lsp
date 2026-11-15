// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ExtensionAPI;
import consulo.component.extension.ExtensionPointName;

/**
 * Provides the default settings for one LSP server.
 * <p>
 * The {@link #getServerId()} must stay stable because it identifies the server configuration.
 */
@ExtensionAPI(ComponentScope.APPLICATION)
public interface LspIntegrationSettingsProvider {
    ExtensionPointName<LspIntegrationSettingsProvider> EP_NAME = ExtensionPointName.create(LspIntegrationSettingsProvider.class);

    /**
     * A stable identifier for this server.
     */
    String getServerId();

    /**
     * The integration provider that starts this server.
     */
    Class<? extends LspIntegrationProvider> getIntegrationProviderClass();

    /**
     * The default configuration used when no project-specific changes exist.
     */
    LspPluginServerConfiguration getDefaultConfiguration();
}

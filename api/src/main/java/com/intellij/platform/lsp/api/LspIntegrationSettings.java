// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ExtensionAPI;
import consulo.project.Project;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Provides effective project settings for LSP server integrations.
 * <p>
 * The settings combine provider defaults with project-specific changes.
 * <p>
 * Services can't be optional, so this is a project-level extension; {@link #getInstanceOrNull} returns the first registered implementation.
 */
@ExtensionAPI(ComponentScope.PROJECT)
public interface LspIntegrationSettings {
    /**
     * Returns the project settings, or {@code null} when no settings service is available.
     */
    static @Nullable LspIntegrationSettings getInstanceOrNull(Project project) {
        List<LspIntegrationSettings> list = project.getExtensionList(LspIntegrationSettings.class);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * Returns the effective configuration for the server identified by {@code serverId}.
     * <p>
     * The result includes the provider defaults and any project-specific changes.
     * The ID must match the stable ID of a registered {@link LspIntegrationSettingsProvider}.
     */
    LspPluginServerConfiguration getPluginConfiguration(String serverId);
}

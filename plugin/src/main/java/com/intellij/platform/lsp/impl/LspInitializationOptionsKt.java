// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl;

import com.intellij.platform.lsp.api.LspPluginServerConfiguration;
import consulo.logging.Logger;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.jspecify.annotations.Nullable;

import java.util.Map;

public final class LspInitializationOptionsKt {
    private static final Logger LOG = Logger.getInstance(LspInitializationOptionsKt.class);

    private LspInitializationOptionsKt() {
    }

    /**
     * Parses JSON initialization options from an LSP server configuration.
     */
    public static @Nullable Object createInitializationOptions(LspPluginServerConfiguration configuration) {
        if (configuration.getInitializationOptions().isBlank()) {
            return null;
        }

        try {
            return new MessageJsonHandler(Map.of()).getGson().fromJson(configuration.getInitializationOptions(), Map.class);
        }
        catch (RuntimeException e) {
            LOG.warn("Invalid JSON in initialization options for '" + configuration.getName() + "': " + e.getMessage());
            return null;
        }
    }
}

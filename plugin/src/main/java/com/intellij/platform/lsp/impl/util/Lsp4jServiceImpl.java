// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.platform.lsp.util.Lsp4jService;
import consulo.annotation.component.ServiceImpl;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

@ServiceImpl
@Singleton
public class Lsp4jServiceImpl extends Lsp4jService {
    @Inject
    public Lsp4jServiceImpl() {
    }

    @Override
    public @Nullable List<Location> extractLocationsFromJson(@Nullable List<Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }

        MessageJsonHandler jsonHandler = new MessageJsonHandler(Map.of());
        for (Object arg : arguments) {
            JsonElement jsonElement = jsonHandler.getGson().toJsonTree(arg);
            if (jsonElement.isJsonArray() && looksLikeLocationArray(jsonElement.getAsJsonArray())) {
                return List.of(jsonHandler.getGson().fromJson(jsonElement, Location[].class));
            }
        }
        return null;
    }

    private static boolean looksLikeLocationArray(JsonArray array) {
        if (array.size() == 0) {
            return false;
        }
        JsonElement first = array.get(0);
        if (!first.isJsonObject()) {
            return false;
        }
        JsonObject obj = first.getAsJsonObject();
        return obj.has("uri") && obj.has("range");
    }
}

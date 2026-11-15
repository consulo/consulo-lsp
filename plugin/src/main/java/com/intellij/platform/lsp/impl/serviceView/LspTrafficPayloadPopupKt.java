package com.intellij.platform.lsp.impl.serviceView;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.intellij.platform.lsp.localize.LspLocalize;

/**
 * Helpers for the traffic payload popup.
 */
public final class LspTrafficPayloadPopupKt {
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private LspTrafficPayloadPopupKt() {
    }

    public static String buildPayloadPopupText(String json, boolean truncated) {
        String pretty;
        try {
            pretty = PRETTY_GSON.toJson(JsonParser.parseString(json));
        }
        catch (Exception e) {
            pretty = json;
        }
        return truncated ? pretty + "\n\n" + LspLocalize.servicesLspTrafficPopupPayloadTruncated().get() : pretty;
    }
}

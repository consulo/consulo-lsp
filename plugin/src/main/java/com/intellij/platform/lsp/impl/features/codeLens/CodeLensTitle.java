// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.features.codeLens;

import consulo.execution.debug.icon.ExecutionDebugIconGroup;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.ui.image.Image;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A code lens title split into the display text and an optional icon.
 */
public class CodeLensTitle {
    private static final Pattern CODICON_TOKEN = Pattern.compile("\\$\\(([\\w-]+)\\) ?");

    private static final Map<String, Image> CODICON_ICONS = Map.of(
        "play", PlatformIconGroup.actionsExecute(),
        "run", PlatformIconGroup.actionsExecute(),
        "debug", ExecutionDebugIconGroup.actionStartdebugger(),
        "debug-alt", ExecutionDebugIconGroup.actionStartdebugger()
    );

    private final String myText;
    private final @Nullable Image myIcon;

    public CodeLensTitle(String text, @Nullable Image icon) {
        myText = text;
        myIcon = icon;
    }

    public String getText() {
        return myText;
    }

    public @Nullable Image getIcon() {
        return myIcon;
    }

    /**
     * Parses the VS Code codicon syntax in a code lens title, for example {@code $(play) Run}.
     * The first known codicon becomes the icon.
     * Every codicon token is removed from the text.
     */
    public static CodeLensTitle parseCodeLensTitle(String title) {
        if (!title.contains("$(")) {
            return new CodeLensTitle(title, null);
        }
        Image icon = null;
        StringBuilder sb = new StringBuilder();
        Matcher matcher = CODICON_TOKEN.matcher(title);
        while (matcher.find()) {
            if (icon == null) {
                icon = CODICON_ICONS.get(matcher.group(1));
            }
            matcher.appendReplacement(sb, "");
        }
        matcher.appendTail(sb);
        String text = sb.toString().trim();
        if (text.isEmpty() && icon == null) {
            return new CodeLensTitle(title, null);
        }
        return new CodeLensTitle(text, icon);
    }
}

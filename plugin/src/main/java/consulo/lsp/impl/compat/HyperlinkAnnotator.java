// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
/*
 * Copyright 2013-2026 consulo.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package consulo.lsp.impl.compat;

import consulo.ui.ex.action.IdeActions;
import consulo.ui.ex.action.Shortcut;
import consulo.ui.ex.keymap.KeymapManager;
import consulo.ui.ex.keymap.util.KeymapUtil;

/**
 * Consulo: the part of IntelliJ's {@code com.intellij.codeInsight.highlighting.HyperlinkAnnotator} the LSP client needs
 * (Consulo's {@code HyperlinkAnnotator} is internal).
 */
public final class HyperlinkAnnotator {
    private HyperlinkAnnotator() {
    }

    /**
     * Returns a comma-separated list of shortcuts assigned to the 'Go To Declaration' action.
     * This list includes up to two shortcuts: at most one mouse shortcut and one keyboard shortcut.
     * If no shortcuts are assigned, this method returns an empty string.
     */
    public static String getGoToDeclarationShortcutsText() {
        Shortcut[] shortcuts = KeymapManager.getInstance().getActiveKeymap().getShortcuts(IdeActions.ACTION_GOTO_DECLARATION);
        String shortcutText = "";
        Shortcut mouseShortcut = null;
        Shortcut keyboardShortcut = null;
        for (Shortcut shortcut : shortcuts) {
            if (!shortcut.isKeyboard() && mouseShortcut == null) {
                mouseShortcut = shortcut;
            }
            if (shortcut.isKeyboard() && keyboardShortcut == null) {
                keyboardShortcut = shortcut;
            }
        }
        if (mouseShortcut != null) {
            shortcutText += KeymapUtil.getShortcutText(mouseShortcut);
            shortcutText = shortcutText.replace("Button1 ", "");
        }
        if (keyboardShortcut != null) {
            if (!shortcutText.isEmpty()) {
                shortcutText += ", ";
            }
            shortcutText += KeymapUtil.getShortcutText(keyboardShortcut);
        }
        return shortcutText;
    }
}

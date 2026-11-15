// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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

import consulo.application.Application;
import consulo.application.ReadAction;
import consulo.language.Language;
import consulo.language.editor.codeBlock.CodeBlockHighlighter;
import consulo.project.Project;
import consulo.util.lang.xml.XmlStringUtil;
import org.jspecify.annotations.Nullable;

/**
 * The quick documentation highlighting helpers the LSP documentation uses, on top of {@link CodeBlockHighlighter}.
 */
public final class QuickDocHighlightingHelper {
    private QuickDocHighlightingHelper() {
    }

    /**
     * Finds a language by the name of a code block (a language id, a display name, a mime type or a file extension).
     */
    public static @Nullable Language guessLanguage(@Nullable String language) {
        if (language == null) {
            return null;
        }
        return Application.get().getInstance(CodeBlockHighlighter.class).guessLanguage(language);
    }

    /**
     * Returns the html of {@code code} highlighted as {@code language}, without a wrapping tag, to be placed into the definition
     * section of the documentation. The code is escaped only when {@code language} is {@code null}.
     */
    public static String getStyledSignatureFragment(Project project, @Nullable Language language, String code) {
        return getStyledFragment(project, language, code, false);
    }

    /**
     * Returns the html of a code block of a documentation text, without a wrapping tag. The indent the lines share is removed.
     */
    public static String getStyledCodeFragment(Project project, @Nullable Language language, String code) {
        return getStyledFragment(project, language, code, true);
    }

    private static String getStyledFragment(Project project, @Nullable Language language, String code, boolean trimIndent) {
        if (language == null || project.isDefault()) {
            return XmlStringUtil.escapeString(code);
        }
        return ReadAction.compute(() -> Application.get().getInstance(CodeBlockHighlighter.class)
            .newHtmlBuilder(project, language, code)
            .trimIndent(trimIndent)
            .build());
    }
}

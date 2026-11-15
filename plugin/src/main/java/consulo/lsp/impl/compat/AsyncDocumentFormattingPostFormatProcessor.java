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

import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.document.util.TextRange;
import consulo.language.codeStyle.CodeStyleSettings;
import consulo.language.codeStyle.ExternalFormatProcessor;
import consulo.language.codeStyle.PostFormatProcessor;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;

/**
 * When an {@link ExternalFormatProcessor} is active for a file, {@code CodeStyleManagerImpl.reformatText}
 * (Reformat Code, postponed reformatting) skips its own formatter and runs only the {@link PostFormatProcessor}s, expecting one of
 * them to call the external formatter. This post-processor does it for the
 * {@link AsyncDocumentFormattingService}s of this plugin; other external formatters are left alone.
 * <p>
 * {@code CodeStyleManager.reformat(element)} / {@code reformatRange} call {@link ExternalFormatProcessor#formatElement} directly
 * and don't come here.
 */
@ExtensionImpl
public class AsyncDocumentFormattingPostFormatProcessor implements PostFormatProcessor {
    @Override
    public PsiElement processElement(PsiElement source, CodeStyleSettings settings) {
        return source;
    }

    @Override
    @RequiredReadAction
    public TextRange processText(PsiFile source, TextRange rangeToReformat, CodeStyleSettings settings) {
        if (ExternalFormatProcessor.activeExternalFormatProcessor(source) instanceof AsyncDocumentFormattingService service) {
            TextRange formattedRange = service.format(source, rangeToReformat, false);
            if (formattedRange != null) {
                return formattedRange;
            }
        }
        return rangeToReformat;
    }
}

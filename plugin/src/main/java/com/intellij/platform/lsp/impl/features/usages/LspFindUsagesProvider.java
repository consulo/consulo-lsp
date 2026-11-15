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
package com.intellij.platform.lsp.impl.features.usages;

import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.language.Language;
import consulo.language.findUsage.EmptyFindUsagesProvider;
import consulo.language.findUsage.FindUsagesProvider;
import consulo.language.plain.PlainTextLanguage;
import consulo.language.psi.PsiElement;

/**
 * Consulo-only (no upstream counterpart). Upstream 'Find Usages' and 'Show Usages' work in any file through the
 * {@code search.targets} data rule ({@link LspSearchTargetsRule}). Consulo's {@code FindUsagesAction}/{@code ShowUsagesAction}
 * are disabled in an editor whose language has no {@link FindUsagesProvider} ({@code FindUsagesInFileAction.isEnabled}
 * checks for {@link EmptyFindUsagesProvider}), which is the case for plain text, the language of files that are opened as
 * text only because an LSP server serves them.
 * <p>
 * This provider enables the actions for plain text and answers {@link #canFindUsagesFor} only for {@link LspSearchTarget};
 * everything else (words scanner, element presentation) is the same as for {@link EmptyFindUsagesProvider}, so plain text
 * indexing and usage view texts do not change. Languages with their own {@code FindUsagesProvider} get LSP usage targets
 * from {@link LspSearchTargetsRule} anyway.
 */
@ExtensionImpl
public class LspFindUsagesProvider implements FindUsagesProvider {
    private static final FindUsagesProvider EMPTY = new EmptyFindUsagesProvider();

    @Override
    public boolean canFindUsagesFor(PsiElement psiElement) {
        return psiElement instanceof LspSearchTarget;
    }

    @Override
    public String getType(PsiElement element) {
        return EMPTY.getType(element);
    }

    @Override
    @RequiredReadAction
    public String getDescriptiveName(PsiElement element) {
        return EMPTY.getDescriptiveName(element);
    }

    @Override
    @RequiredReadAction
    public String getNodeText(PsiElement element, boolean useFullName) {
        return EMPTY.getNodeText(element, useFullName);
    }

    @Override
    public Language getLanguage() {
        return PlainTextLanguage.INSTANCE;
    }
}

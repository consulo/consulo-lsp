// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
import consulo.language.editor.refactoring.ImportOptimizer;
import consulo.language.psi.PsiFile;

import java.util.Set;

/**
 * Minimal port of IntelliJ's {@code com.intellij.formatting.service.FormattingService}: the members the LSP formatting service
 * implements. Consulo has no formatting service extension point; {@link AsyncDocumentFormattingService} plugs into Consulo's
 * {@link consulo.language.codeStyle.ExternalFormatProcessor} instead.
 */
public interface FormattingService {
    enum Feature {
        /**
         * The service supports formatting of document ranges (fragments).
         */
        FORMAT_FRAGMENTS,

        /**
         * The service supports quick ad-hoc formatting while typing.
         */
        AD_HOC_FORMATTING,

        /**
         * The service supports import optimization.
         */
        OPTIMIZE_IMPORTS
    }

    /**
     * @return A set of features supported by the formatting service.
     */
    Set<Feature> getFeatures();

    /**
     * @return {@code true} if the service can format the file (full-file formatting).
     */
    @RequiredReadAction
    boolean canFormat(PsiFile file);

    /**
     * @return {@code true} if the service can format the file with the given features.
     */
    @RequiredReadAction
    default boolean canFormat(PsiFile file, Feature... features) {
        if (!canFormat(file)) {
            return false;
        }
        for (Feature feature : features) {
            if (!getFeatures().contains(feature)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return The import optimizers of the service for the file.
     */
    Set<ImportOptimizer> getImportOptimizers(PsiFile file);
}

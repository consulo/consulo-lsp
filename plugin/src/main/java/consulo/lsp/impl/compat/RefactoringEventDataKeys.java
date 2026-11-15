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

import consulo.language.psi.PsiElement;
import consulo.util.dataholder.Key;

import java.util.Map;

/**
 * The {@code TARGET_ELEMENT_KEY} and {@code RENAMES_KEY} refactoring event data keys, which
 * {@link consulo.language.editor.refactoring.event.RefactoringEventData} does not have.
 * <p>
 * Consulo's {@code RenameProcessor} / {@code MoveFilesOrDirectoriesProcessor} put only the elements into the before-data, so these
 * keys are never set until the platform adds them (then the platform keys must be used instead of these).
 */
public final class RefactoringEventDataKeys {
    /**
     * The target directory of a move refactoring.
     */
    public static final Key<PsiElement> TARGET_ELEMENT_KEY = Key.create("targetElement");

    /**
     * All renames of a rename refactoring: element to its new name.
     */
    public static final Key<Map<PsiElement, String>> RENAMES_KEY = Key.create("renames");

    private RefactoringEventDataKeys() {
    }
}

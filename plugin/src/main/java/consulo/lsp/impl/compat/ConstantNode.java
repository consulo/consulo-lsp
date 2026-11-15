// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
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

import consulo.language.editor.completion.lookup.LookupElement;
import consulo.language.editor.completion.lookup.LookupElementBuilder;
import consulo.language.editor.template.Expression;
import consulo.language.editor.template.ExpressionContext;
import consulo.language.editor.template.Result;
import consulo.language.editor.template.TextResult;

import java.util.Collection;

/**
 * IntelliJ's {@code com.intellij.codeInsight.template.impl.ConstantNode}: Consulo's
 * {@link consulo.language.editor.template.ConstantNode} has no lookup items ({@link #withLookupStrings}).
 * The lookup focus degree and the popup advertisement are not ported.
 */
public final class ConstantNode extends Expression {
    private final Result myValue;
    private final LookupElement[] myLookupElements;

    public ConstantNode(String value) {
        this(new TextResult(value), LookupElement.EMPTY_ARRAY);
    }

    private ConstantNode(Result value, LookupElement[] lookupElements) {
        myValue = value;
        myLookupElements = lookupElements;
    }

    public ConstantNode withLookupItems(LookupElement... lookupElements) {
        return new ConstantNode(myValue, lookupElements);
    }

    public ConstantNode withLookupStrings(Collection<String> lookupStrings) {
        LookupElement[] lookupElements = new LookupElement[lookupStrings.size()];
        int i = 0;
        for (String lookupString : lookupStrings) {
            lookupElements[i++] = LookupElementBuilder.create(lookupString);
        }
        return new ConstantNode(myValue, lookupElements);
    }

    @Override
    public Result calculateResult(ExpressionContext context) {
        return myValue;
    }

    @Override
    public Result calculateQuickResult(ExpressionContext context) {
        return myValue;
    }

    @Override
    public LookupElement[] calculateLookupItems(ExpressionContext context) {
        return myLookupElements;
    }
}

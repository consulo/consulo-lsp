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

package com.intellij.platform.lsp.impl.features.completion;

import consulo.language.editor.completion.lookup.InsertionContext;
import consulo.language.editor.template.Expression;
import consulo.language.editor.template.Template;
import consulo.language.editor.template.TemplateManager;
import consulo.language.editor.template.Variable;
import consulo.lsp.impl.compat.ConstantNode;
import consulo.project.Project;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;

import static com.intellij.platform.lsp.impl.features.completion.LspCompletionItemInsertHandler.END_VARIABLE;
import static com.intellij.platform.lsp.impl.features.completion.LspCompletionItemInsertHandler.LSP_VARIABLE_TRANSFORMATIONS;
import static com.intellij.platform.lsp.impl.features.completion.LspCompletionItemInsertHandler.TEMPLATE_VARIABLE_REGEX;
import static com.intellij.platform.lsp.impl.features.completion.LspCompletionItemInsertHandler.VARIABLE_DEFAULT_VALUE_GROUP;
import static com.intellij.platform.lsp.impl.features.completion.LspCompletionItemInsertHandler.VARIABLE_NAME_PREFIX;
import static com.intellij.platform.lsp.impl.features.completion.LspCompletionItemInsertHandler.VARIABLE_NUMBER_GROUPS;

public class SnippetToTemplateConverter {
    private final Project myProject;
    private final String myRawSnippetText;

    public SnippetToTemplateConverter(Project project, String rawSnippetText) {
        myProject = project;
        myRawSnippetText = rawSnippetText;
    }

    public Template computeTemplate(InsertionContext context) {
        List<TemplateVariable> givenVariables = parseTemplateVariables(context);

        Template template = TemplateManager.getInstance(myProject)
            .createTemplate("", "", computeTemplateText(hasEndVariableDeclared(givenVariables)));
        // Template has no addVariable(Variable)
        for (Variable variable : createIjTemplateVariables(givenVariables)) {
            template.addVariable(
                variable.getName(),
                variable.getExpression(),
                variable.getDefaultValueExpression(),
                variable.isAlwaysStopAt(),
                variable.skipOnStart()
            );
        }
        template.setInline(false);
        return template;
    }

    private String computeTemplateText(boolean hasExplicitEndVariable) {
        String rawTemplateText = replaceGivenVariables();
        return hasExplicitEndVariable
            ? rawTemplateText
            : appendEndVariable(rawTemplateText);
    }

    private String replaceGivenVariables() {
        Matcher matcher = TEMPLATE_VARIABLE_REGEX.matcher(myRawSnippetText);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Integer index = toIntOrNull(groupValueByIndexOrEmpty(matcher, VARIABLE_NUMBER_GROUPS));
            String replacement;
            if (index == null) {
                replacement = "";
            }
            else if (index == 0) {
                replacement = END_VARIABLE;
            }
            else {
                replacement = "$" + VARIABLE_NAME_PREFIX + index + "$";
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String appendEndVariable(String templateText) {
        return templateText + END_VARIABLE;
    }

    public String computeEffectiveLookup() {
        return TEMPLATE_VARIABLE_REGEX.matcher(myRawSnippetText).replaceAll("");
    }

    private boolean hasEndVariableDeclared(List<TemplateVariable> variables) {
        for (TemplateVariable variable : variables) {
            if (variable.getIndex() == 0) {
                return true;
            }
        }
        return false;
    }

    private List<Variable> createIjTemplateVariables(List<TemplateVariable> parsedVariables) {
        List<TemplateVariable> distinct = new ArrayList<>();
        Set<Integer> seenIndexes = new HashSet<>();
        for (TemplateVariable variable : parsedVariables) {
            if (seenIndexes.add(variable.getIndex())) {
                distinct.add(variable);
            }
        }
        distinct.sort(Comparator.comparingInt(TemplateVariable::getIndex));
        List<Variable> result = new ArrayList<>(distinct.size());
        for (TemplateVariable variable : distinct) {
            result.add(mapTemplateVariable(variable));
        }
        return result;
    }

    private Variable mapTemplateVariable(TemplateVariable variable) {
        String variableName = VARIABLE_NAME_PREFIX + variable.getIndex();
        Expression expression;
        if (variable instanceof TemplateVariable.Unknown) {
            expression = new ConstantNode("");
        }
        else if (variable instanceof TemplateVariable.PlaceHolder placeHolder) {
            expression = new ConstantNode(placeHolder.getDefaultValue());
        }
        else {
            expression = new ConstantNode("").withLookupStrings(((TemplateVariable.WithCompletion) variable).getCompletionVariants());
        }
        return new Variable(variableName, expression, expression, true, false);
    }

    private List<TemplateVariable> parseTemplateVariables(InsertionContext context) {
        List<TemplateVariable> result = new ArrayList<>();
        Matcher matcher = TEMPLATE_VARIABLE_REGEX.matcher(myRawSnippetText);
        while (matcher.find()) {
            TemplateVariable variable = computeTemplateVariable(matcher, context);
            if (variable != null && variable.getIndex() != 0) {
                result.add(variable);
            }
        }
        return result;
    }

    private @Nullable TemplateVariable computeTemplateVariable(Matcher match, InsertionContext context) {
        Integer variableNumber = toIntOrNull(groupValueByIndexOrEmpty(match, VARIABLE_NUMBER_GROUPS));
        if (variableNumber == null) {
            return null;
        }
        String rawPlaceHolder = groupValueByIndexOrEmpty(match, VARIABLE_DEFAULT_VALUE_GROUP);
        return createVariableFromPlaceHolder(variableNumber, rawPlaceHolder, context);
    }

    private TemplateVariable createVariableFromPlaceHolder(int variableNumber, String rawPlaceHolder, InsertionContext context) {
        if (rawPlaceHolder.startsWith("|") && rawPlaceHolder.endsWith("|")) {
            return new TemplateVariable.WithCompletion(variableNumber, Arrays.asList(trimPipes(rawPlaceHolder).split(",", -1)));
        }
        Function<InsertionContext, @Nullable String> transformation = LSP_VARIABLE_TRANSFORMATIONS.get(rawPlaceHolder);
        if (transformation != null) {
            String value = transformation.apply(context);
            return new TemplateVariable.PlaceHolder(variableNumber, value != null ? value : rawPlaceHolder);
        }
        if (!rawPlaceHolder.contains("|") && !rawPlaceHolder.contains(",")) {
            return new TemplateVariable.PlaceHolder(variableNumber, rawPlaceHolder);
        }
        return new TemplateVariable.Unknown(variableNumber);
    }

    // removes the leading and trailing '|' characters
    private static String trimPipes(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == '|') {
            start++;
        }
        while (end > start && text.charAt(end - 1) == '|') {
            end--;
        }
        return text.substring(start, end);
    }

    private static @Nullable Integer toIntOrNull(String text) {
        try {
            return Integer.parseInt(text);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private String groupValueByIndexOrEmpty(Matcher matchResult, int... groupIndexes) {
        for (int groupIndex : groupIndexes) {
            if (matchResult.groupCount() + 1 > groupIndex) {
                String value = matchResult.group(groupIndex);
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        }
        return "";
    }

    private sealed interface TemplateVariable {
        int getIndex();

        final class Unknown implements TemplateVariable {
            private final int myIndex;

            Unknown(int index) {
                myIndex = index;
            }

            @Override
            public int getIndex() {
                return myIndex;
            }
        }

        final class PlaceHolder implements TemplateVariable {
            private final int myIndex;
            private final String myDefaultValue;

            PlaceHolder(int index, String defaultValue) {
                myIndex = index;
                myDefaultValue = defaultValue;
            }

            @Override
            public int getIndex() {
                return myIndex;
            }

            public String getDefaultValue() {
                return myDefaultValue;
            }
        }

        final class WithCompletion implements TemplateVariable {
            private final int myIndex;
            private final List<String> myCompletionVariants;

            WithCompletion(int index, List<String> completionVariants) {
                myIndex = index;
                myCompletionVariants = completionVariants;
            }

            @Override
            public int getIndex() {
                return myIndex;
            }

            public List<String> getCompletionVariants() {
                return myCompletionVariants;
            }
        }
    }
}

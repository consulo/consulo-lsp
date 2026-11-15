package com.intellij.platform.lsp.impl.features.documentation;

import consulo.annotation.access.RequiredReadAction;
import consulo.language.editor.documentation.DocumentationMarkup;
import consulo.logging.Logger;
import consulo.lsp.impl.compat.DocMarkdownToHtmlConverter;
import consulo.lsp.impl.compat.QuickDocHighlightingHelper;
import consulo.project.Project;
import consulo.util.lang.StringUtil;
import consulo.util.lang.xml.XmlStringUtil;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data class containing parsed information about the
 * documentation retrieved from the LSP server.
 * <p>
 * The {@link #getDefinitionCodeBlock()} contains raw code of the
 * returned definition. It is supposed to be shown
 * in the `definition` section of the QuickDoc. It should
 * be highlighted according to the rules for {@link #getDefinitionLanguage()}.
 * <p>
 * The {@link #getDescription()} contains description in {@link #getDescriptionMarkup()} format.
 */
public final class LspDocumentationData {
    private final @Nullable String myDefinitionCodeBlock;
    private final @Nullable String myDefinitionLanguage;
    private final @Nullable String myDescription;
    private final DescriptionMarkup myDescriptionMarkup;

    public LspDocumentationData() {
        this(null);
    }

    public LspDocumentationData(@Nullable String definitionCodeBlock) {
        this(definitionCodeBlock, null);
    }

    public LspDocumentationData(@Nullable String definitionCodeBlock, @Nullable String definitionLanguage) {
        this(definitionCodeBlock, definitionLanguage, null);
    }

    public LspDocumentationData(@Nullable String definitionCodeBlock, @Nullable String definitionLanguage, @Nullable String description) {
        this(definitionCodeBlock, definitionLanguage, description, DescriptionMarkup.PLAIN);
    }

    public LspDocumentationData(
        @Nullable String definitionCodeBlock,
        @Nullable String definitionLanguage,
        @Nullable String description,
        DescriptionMarkup descriptionMarkup
    ) {
        myDefinitionCodeBlock = definitionCodeBlock;
        myDefinitionLanguage = definitionLanguage;
        myDescription = description;
        myDescriptionMarkup = descriptionMarkup;
    }

    public static LspDocumentationData createLspDocumentationData(MarkupContent markupContent) {
        if (!MarkupKind.MARKDOWN.equals(markupContent.getKind())) {
            if (!MarkupKind.PLAINTEXT.equals(markupContent.getKind())) {
                Logger.getInstance(LspDocumentationData.class)
                    .warn("Unexpected MarkupKind: " + markupContent.getKind() + ", treating as plain text");
            }
            return new LspDocumentationData(null, null, markupContent.getValue());
        }
        String contents = StringUtil.convertLineSeparators(markupContent.getValue());
        LeadingCodeFence definitionFence = parseLeadingCodeFence(contents);
        if (definitionFence == null) {
            return new LspDocumentationData(null, null, contents, DescriptionMarkup.MARKDOWN);
        }
        return new LspDocumentationData(
            definitionFence.code,
            definitionFence.language,
            definitionFence.rest,
            DescriptionMarkup.MARKDOWN
        );
    }

    /**
     * A fenced code block that opens a markdown text, and the text after it.
     */
    private static final class LeadingCodeFence {
        private final @Nullable String language;
        private final String code;
        private final String rest;

        private LeadingCodeFence(@Nullable String language, String code, String rest) {
            this.language = language;
            this.code = code;
            this.rest = rest;
        }
    }

    /**
     * Parses a backtick code fence at the start of {@code contents}.
     * The fence has three or more backticks, and the closing fence has at least as many backticks as the opening one.
     * Returns null when the text does not start with a closed fence.
     */
    private static @Nullable LeadingCodeFence parseLeadingCodeFence(String contents) {
        int fenceLength = 0;
        while (fenceLength < contents.length() && contents.charAt(fenceLength) == '`') {
            fenceLength++;
        }
        if (fenceLength < 3) {
            return null;
        }
        int infoEnd = contents.indexOf('\n');
        if (infoEnd < 0) {
            return null;
        }
        Matcher closingFence = Pattern.compile("^`{" + fenceLength + ",}[ \\t]*$", Pattern.MULTILINE).matcher(contents);
        if (!closingFence.find(infoEnd + 1)) {
            return null;
        }
        String info = contents.substring(fenceLength, infoEnd).trim();
        int spaceIndex = info.indexOf(' ');
        String language = spaceIndex >= 0 ? info.substring(0, spaceIndex) : info;
        String code = trimIndent(contents.substring(infoEnd + 1, closingFence.start())).stripTrailing();
        String rest = contents.substring(closingFence.end());
        return new LeadingCodeFence(language.isEmpty() ? null : language, code, rest);
    }

    /**
     * Kotlin's {@code String.trimIndent()}: removes the blank first and last lines and the indent the non-blank lines share.
     */
    private static String trimIndent(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\r\n|\n|\r", -1)));
        int minCommonIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (!line.isBlank()) {
                int indent = 0;
                while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) {
                    indent++;
                }
                minCommonIndent = Math.min(minCommonIndent, indent);
            }
        }
        if (minCommonIndent == Integer.MAX_VALUE) {
            minCommonIndent = 0;
        }

        StringBuilder result = new StringBuilder(text.length());
        int lastIndex = lines.size() - 1;
        boolean first = true;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if ((i == 0 || i == lastIndex) && line.isBlank()) {
                continue;
            }
            if (!first) {
                result.append('\n');
            }
            first = false;
            result.append(line.length() > minCommonIndent ? line.substring(minCommonIndent) : (line.isBlank() ? "" : line));
        }
        return result.toString();
    }

    public @Nullable String getDefinitionCodeBlock() {
        return myDefinitionCodeBlock;
    }

    public @Nullable String getDefinitionLanguage() {
        return myDefinitionLanguage;
    }

    public @Nullable String getDescription() {
        return myDescription;
    }

    public DescriptionMarkup getDescriptionMarkup() {
        return myDescriptionMarkup;
    }

    /**
     * Consulo: returns the html of the documentation (IntelliJ returns a {@code DocumentationResult.Documentation}, which does not
     * exist in Consulo; {@code DocumentationProvider.generateDoc()} returns html).
     */
    @RequiredReadAction
    public String toQuickDocHtml(Project project) {
        String htmlDefinition = null;
        if (myDefinitionCodeBlock != null) {
            String fragment = QuickDocHighlightingHelper.getStyledSignatureFragment(
                project, QuickDocHighlightingHelper.guessLanguage(myDefinitionLanguage), myDefinitionCodeBlock);
            htmlDefinition = DocumentationMarkup.DEFINITION_START + fragment + DocumentationMarkup.DEFINITION_END;
        }

        String htmlDescription = null;
        if (myDescription != null) {
            String description = switch (myDescriptionMarkup) {
                case MARKDOWN -> StringUtil.trimStart(
                    DocMarkdownToHtmlConverter.convert(
                        project, myDescription, QuickDocHighlightingHelper.guessLanguage(myDefinitionLanguage)).trim(),
                    "<hr />");
                case PLAIN -> XmlStringUtil.escapeString(myDescription);
            };
            if (!description.isBlank()) {
                htmlDescription = DocumentationMarkup.CONTENT_START + description + DocumentationMarkup.CONTENT_END;
            }
        }
        return (htmlDefinition != null ? htmlDefinition : "") + (htmlDescription != null ? htmlDescription : "");
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LspDocumentationData that)) {
            return false;
        }
        return Objects.equals(myDefinitionCodeBlock, that.myDefinitionCodeBlock) &&
            Objects.equals(myDefinitionLanguage, that.myDefinitionLanguage) &&
            Objects.equals(myDescription, that.myDescription) &&
            myDescriptionMarkup == that.myDescriptionMarkup;
    }

    @Override
    public int hashCode() {
        return Objects.hash(myDefinitionCodeBlock, myDefinitionLanguage, myDescription, myDescriptionMarkup);
    }

    @Override
    public String toString() {
        return "LspDocumentationData(definitionCodeBlock=" + myDefinitionCodeBlock +
            ", definitionLanguage=" + myDefinitionLanguage +
            ", description=" + myDescription +
            ", descriptionMarkup=" + myDescriptionMarkup + ")";
    }

    public enum DescriptionMarkup {
        PLAIN,
        MARKDOWN,
    }
}

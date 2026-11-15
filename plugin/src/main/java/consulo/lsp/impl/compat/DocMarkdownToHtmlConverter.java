package consulo.lsp.impl.compat;

import consulo.language.Language;
import consulo.project.Project;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * IntelliJ's {@code com.intellij.markdown.utils.doc.DocMarkdownToHtmlConverter} (platform/markdown-utils, which is based on the
 * JetBrains markdown library) on <a href="https://github.com/commonmark/commonmark-java">commonmark-java</a> with the GFM tables and
 * strikethrough extensions, which the Consulo platform ships.
 * <p>
 * Code blocks are highlighted by {@link QuickDocHighlightingHelper}: a fenced code block as the language of its info string,
 * a code block without a known language as {@code defaultLanguage}. The html of the markdown is kept as is.
 */
public final class DocMarkdownToHtmlConverter {
    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create(), StrikethroughExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

    private DocMarkdownToHtmlConverter() {
    }

    public static String convert(Project project, String markdownText) {
        return convert(project, markdownText, null);
    }

    public static String convert(Project project, String markdownText, @Nullable Language defaultLanguage) {
        Node document = PARSER.parse(markdownText);
        HtmlRenderer renderer = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .nodeRendererFactory(context -> new CodeBlockRenderer(context, project, defaultLanguage))
            .build();
        return renderer.render(document);
    }

    private static final class CodeBlockRenderer implements NodeRenderer {
        private final HtmlWriter myWriter;
        private final Project myProject;
        private final @Nullable Language myDefaultLanguage;

        private CodeBlockRenderer(HtmlNodeRendererContext context, Project project, @Nullable Language defaultLanguage) {
            myWriter = context.getWriter();
            myProject = project;
            myDefaultLanguage = defaultLanguage;
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(FencedCodeBlock.class, IndentedCodeBlock.class);
        }

        @Override
        public void render(Node node) {
            String code;
            Language language = myDefaultLanguage;
            if (node instanceof FencedCodeBlock fencedCodeBlock) {
                code = fencedCodeBlock.getLiteral();
                String info = fencedCodeBlock.getInfo();
                if (info != null && !info.isBlank()) {
                    String languageName = info.trim().split("\\s+", 2)[0];
                    Language infoLanguage = QuickDocHighlightingHelper.guessLanguage(languageName);
                    if (infoLanguage != null) {
                        language = infoLanguage;
                    }
                }
            }
            else {
                code = ((IndentedCodeBlock) node).getLiteral();
            }
            if (code.endsWith("\n")) {
                code = code.substring(0, code.length() - 1);
            }

            myWriter.line();
            myWriter.raw("<pre><code>");
            myWriter.raw(QuickDocHighlightingHelper.getStyledCodeFragment(myProject, language, code));
            myWriter.raw("</code></pre>");
            myWriter.line();
        }
    }
}

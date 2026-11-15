// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
 * Converts documentation markdown to html with <a href="https://github.com/commonmark/commonmark-java">commonmark-java</a> and
 * its GFM tables and strikethrough extensions, which the platform ships.
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

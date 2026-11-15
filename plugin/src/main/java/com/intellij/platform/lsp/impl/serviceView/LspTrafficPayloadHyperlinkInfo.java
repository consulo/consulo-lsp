package com.intellij.platform.lsp.impl.serviceView;

import consulo.execution.ui.console.HyperlinkInfoBase;
import consulo.language.editor.ui.awt.AWTLanguageEditorUtil;
import consulo.project.Project;
import consulo.ui.ex.RelativePoint;
import consulo.ui.ex.awt.JBScrollPane;
import consulo.ui.ex.awt.JBTextArea;
import consulo.ui.ex.awt.JBUI;
import consulo.ui.ex.popup.JBPopup;
import consulo.ui.ex.popup.JBPopupFactory;
import org.jspecify.annotations.Nullable;

/**
 * Attached to the header of an {@code IN}/{@code OUT} traffic line printed by {@link LspClientConsole#printTraffic}.
 * On click, shows a popup with the pretty-printed JSON-RPC payload at the click location.
 * <p>
 * The payload is stored raw and formatted lazily on click, so no JSON formatting happens on the LSP protocol threads.
 * <p>
 * The popup text is built by {@link LspTrafficPayloadPopupKt#buildPayloadPopupText}.
 */
public class LspTrafficPayloadHyperlinkInfo extends HyperlinkInfoBase {
    private final String myHeader;
    private final String myJson;
    private final boolean myTruncated;

    public LspTrafficPayloadHyperlinkInfo(String header, String json, boolean truncated) {
        myHeader = header;
        myJson = json;
        myTruncated = truncated;
    }

    public String getHeader() {
        return myHeader;
    }

    public String getJson() {
        return myJson;
    }

    public boolean getTruncated() {
        return myTruncated;
    }

    @Override
    public void navigate(Project project, @Nullable RelativePoint hyperlinkLocationPoint) {
        String text = LspTrafficPayloadPopupKt.buildPayloadPopupText(myJson, myTruncated);

        JBTextArea textArea = new JBTextArea(text);
        textArea.setEditable(false);
        textArea.setFont(AWTLanguageEditorUtil.getEditorFont());
        textArea.setCaretPosition(0);
        JBScrollPane scrollPane = new JBScrollPane(textArea);
        scrollPane.setPreferredSize(JBUI.size(700, 400));
        scrollPane.setBorder(JBUI.Borders.empty());

        JBPopup popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, textArea)
            .setTitle(myHeader)
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup();

        if (hyperlinkLocationPoint != null) {
            popup.show(hyperlinkLocationPoint);
        }
        else {
            popup.showCenteredInCurrentWindow(project);
        }
    }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.util;

import com.intellij.platform.lsp.api.LspClient;
import consulo.application.ReadAction;
import consulo.codeEditor.Editor;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.document.util.TextRange;
import consulo.fileEditor.FileEditorManager;
import consulo.navigation.OpenFileDescriptor;
import consulo.navigation.OpenFileDescriptorFactory;
import consulo.project.Project;
import consulo.ui.ex.RelativePoint;
import consulo.ui.ex.SimpleTextAttributes;
import consulo.ui.ex.awt.ColoredListCellRenderer;
import consulo.ui.ex.popup.JBPopup;
import consulo.ui.ex.popup.JBPopupFactory;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;
import org.jspecify.annotations.Nullable;

import javax.swing.JList;
import java.awt.event.MouseEvent;
import java.util.List;

public final class LspNavigationUtilsKt {
    private LspNavigationUtilsKt() {
    }

    /**
     * Navigates to a single LSP location or shows a popup if there are multiple locations.
     *
     * @param client     The LSP server instance
     * @param locations  The list of locations to navigate to
     * @param title      The title for the popup if multiple locations exist (e.g., "References", "Implementations")
     * @param mouseEvent Mouse event for popup positioning
     */
    public static void navigateOrShowPopup(LspClient client, List<Location> locations, String title, @Nullable MouseEvent mouseEvent) {
        if (locations.isEmpty()) {
            return;
        }
        if (locations.size() == 1) {
            navigateToLocation(client, locations.get(0));
        }
        else {
            showLspNavigationPopup(client, locations, title, mouseEvent);
        }
    }

    public static void navigateToLocation(LspClient client, Location location) {
        VirtualFile file = client.getDescriptor().findFileByUri(location.getUri());
        if (file == null) {
            return;
        }
        OpenFileDescriptor descriptor = OpenFileDescriptorFactory.getInstance(client.getProject())
            .newBuilder(file)
            .line(location.getRange().getStart().getLine())
            .column(location.getRange().getStart().getCharacter())
            .build();
        FileEditorManager.getInstance(client.getProject()).openTextEditor(descriptor, true);
    }

    /**
     * Creates and shows a popup for LSP locations (references, implementations, etc.).
     * Automatically resolves file content and handles navigation.
     * <p>
     * Consider using {@link #navigateOrShowPopup} instead if you want to navigate directly when there's only one location.
     */
    public static void showLspNavigationPopup(LspClient client, List<Location> locations, String title, @Nullable MouseEvent mouseEvent) {
        if (locations.isEmpty()) {
            return;
        }

        Project project = client.getProject();
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            return;
        }

        JBPopup popup = JBPopupFactory.getInstance().createPopupChooserBuilder(locations)
            .setTitle(title)
            .setRenderer(new ColoredListCellRenderer<Location>() {
                @Override
                @SuppressWarnings("HardCodedStringLiteral")
                protected void customizeCellRenderer(JList<? extends Location> list,
                                                     Location value,
                                                     int index,
                                                     boolean selected,
                                                     boolean hasFocus) {
                    VirtualFile file = client.getDescriptor().findFileByUri(value.getUri());
                    if (file == null) {
                        append(value.getUri(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                        return;
                    }
                    setIcon(file.getFileType().getIcon());

                    Document document = ReadAction.compute(() -> FileDocumentManager.getInstance().getDocument(file));
                    Range range = value.getRange();

                    // Handle case where file exists but content/range is invalid
                    if (document == null || range.getStart().getLine() < 0 || range.getStart().getLine() >= document.getLineCount()) {
                        append(" " + file.getName() + ":" + (range.getStart().getLine() + 1), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                        return;
                    }

                    int lineStart = document.getLineStartOffset(range.getStart().getLine());
                    int lineEnd = document.getLineEndOffset(range.getStart().getLine());
                    String lineText = document.getText(new TextRange(lineStart, lineEnd)).trim();

                    int matchStart = Math.clamp(range.getStart().getCharacter(), 0, lineText.length());
                    int matchEnd = Math.clamp(range.getEnd().getCharacter(), matchStart, lineText.length());

                    boolean isSingleLine = range.getStart().getLine() == range.getEnd().getLine();
                    boolean isPartialLine = isSingleLine && (matchStart > 0 || matchEnd < lineText.length());
                    boolean shouldHighlight = isPartialLine && matchStart < matchEnd;

                    if (shouldHighlight) {
                        append(lineText.substring(0, matchStart));
                        append(lineText.substring(matchStart, matchEnd), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                        append(lineText.substring(matchEnd));
                    }
                    else {
                        append(lineText);
                        if (!isSingleLine) {
                            int additionalLines = range.getEnd().getLine() - range.getStart().getLine();
                            append(" +" + additionalLines + " " + (additionalLines == 1 ? "line" : "lines"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                        }
                    }

                    append(" " + file.getName() + ":" + (range.getStart().getLine() + 1), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
            })
            .setItemChosenCallback(location -> navigateToLocation(client, location))
            .createPopup();

        if (mouseEvent != null) {
            popup.show(new RelativePoint(mouseEvent));
        }
        else {
            editor.showPopupInBestPositionFor(popup);
        }
    }
}

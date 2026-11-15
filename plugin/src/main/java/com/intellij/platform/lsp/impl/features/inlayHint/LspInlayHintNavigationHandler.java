package com.intellij.platform.lsp.impl.features.inlayHint;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import consulo.annotation.component.ExtensionImpl;
import consulo.codeEditor.Editor;
import consulo.codeEditor.event.EditorMouseEvent;
import consulo.language.editor.inlay.InlayActionHandler;
import consulo.language.editor.inlay.InlayActionPayload;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.jspecify.annotations.Nullable;

/**
 * Consulo-specific: handles a click on an inlay hint label part that has a {@code location}
 * (upstream: {@code factory.reference(presentation) { navigateTo(...) }}).
 * The declarative inlay payload is a string, so the location is encoded as {@code line:character:uri};
 * the client descriptor resolving the URI is looked up among the clients that have the editor's file open.
 */
@ExtensionImpl
public class LspInlayHintNavigationHandler implements InlayActionHandler {
    public static final String HANDLER_ID = "lsp.inlay.hint.navigation";

    static String encodeLocation(Location location) {
        Range range = location.getRange();
        if (range == null || range.getStart() == null) {
            return "-1:-1:" + location.getUri();
        }
        return range.getStart().getLine() + ":" + range.getStart().getCharacter() + ":" + location.getUri();
    }

    @Override
    public String getHandlerId() {
        return HANDLER_ID;
    }

    @Override
    @RequiredUIAccess
    public void handleClick(EditorMouseEvent e, InlayActionPayload payload) {
        handleClick(e.getEditor(), payload);
    }

    @Override
    @RequiredUIAccess
    @SuppressWarnings("deprecation")
    public void handleClick(Editor editor, InlayActionPayload payload) {
        if (!(payload instanceof InlayActionPayload.StringInlayActionPayload stringPayload)) {
            return;
        }
        Project project = editor.getProject();
        VirtualFile file = editor.getVirtualFile();
        if (project == null || file == null) {
            return;
        }

        String[] parts = stringPayload.getText().split(":", 3);
        if (parts.length != 3) {
            return;
        }
        int line;
        int character;
        try {
            line = Integer.parseInt(parts[0]);
            character = Integer.parseInt(parts[1]);
        }
        catch (NumberFormatException ex) {
            return;
        }
        String uri = parts[2];
        @Nullable Range range = line < 0 ? null : new Range(new Position(line, character), new Position(line, character));

        for (LspClientImpl client : LspClientManagerImpl.getInstanceImpl(project).getClientsWithThisFileOpen(file)) {
            if (client.getDescriptor().findFileByUri(uri) != null) {
                LspInlayHintRenderingKt.navigateTo(client.getDescriptor(), project, uri, range);
                return;
            }
        }
    }
}

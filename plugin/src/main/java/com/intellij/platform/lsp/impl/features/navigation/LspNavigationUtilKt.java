package com.intellij.platform.lsp.impl.features.navigation;

import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.fileEditor.FileEditorManager;
import consulo.navigation.OpenFileDescriptorFactory;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import org.eclipse.lsp4j.Position;

import java.util.List;

public final class LspNavigationUtilKt {
    private LspNavigationUtilKt() {
    }

    /**
     * Path of {@code fileOrDir} relative to the closest of the server {@code roots}, for showing where a reference or a link leads.
     * <p>
     * Falls back to the full path for anything outside the roots -- an SDK, a dependency cache, a file elsewhere on the disk --
     * where the absolute location is what tells the user where they are going.
     */
    public static String getPresentablePath(VirtualFile fileOrDir, List<VirtualFile> roots) {
        String best = null;
        for (VirtualFile root : roots) {
            String relativePath = VirtualFileUtil.getRelativePath(fileOrDir, root);
            if (relativePath == null || relativePath.isEmpty()) {
                continue;
            }
            if (best == null || relativePath.length() < best.length()) {
                best = relativePath;
            }
        }
        return best != null ? best : fileOrDir.getPresentableUrl();
    }

    public static void navigateToLspPosition(
        VirtualFile virtualFile,
        Project project,
        Position position,
        boolean requestFocus
    ) {
        Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
        if (document == null) {
            return;
        }

        Integer offset = Lsp4jUtilKt.getOffsetInDocument(document, position);
        if (offset != null) {
            FileEditorManager.getInstance(project).openEditor(
                OpenFileDescriptorFactory.getInstance(project).newBuilder(virtualFile).offset(offset).build(),
                requestFocus
            );
        }
    }
}

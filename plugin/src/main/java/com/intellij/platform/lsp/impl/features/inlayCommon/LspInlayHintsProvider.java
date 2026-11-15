package com.intellij.platform.lsp.impl.features.inlayCommon;

import consulo.application.dumb.DumbAware;
import consulo.codeEditor.Editor;
import consulo.language.Language;
import consulo.language.editor.inlay.DeclarativeInlayHintsCollector;
import consulo.language.editor.inlay.DeclarativeInlayHintsProvider;
import consulo.language.editor.inlay.DeclarativeInlayTreeSink;
import consulo.language.editor.inlay.InlayGroup;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.language.psi.PsiFile;
import consulo.localize.LocalizeValue;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

/**
 * The single daemon-pass hook for all LSP inline inlays. The daemon runs this per editor (including
 * splits); it paints BOTH inlay hints and document-color swatches collected by {@link LspInlayApplier}.
 * <p>
 * Consulo: upstream is a hidden legacy {@code InlayHintsProvider} that only schedules {@link LspInlayApplier}, which then
 * paints out of band. Here it is a {@link DeclarativeInlayHintsProvider} whose collector adds the LSP inlays to the
 * declarative sink. It is not an extension: {@link LspInlayHintsProviderFactory} offers it for every language and keeps
 * it out of the settings (upstream {@code isVisibleInSettings = false}).
 */
public class LspInlayHintsProvider implements DeclarativeInlayHintsProvider, DumbAware {
    public static final String PROVIDER_ID = "lsp.inlay.hints";

    public static final LspInlayHintsProvider INSTANCE = new LspInlayHintsProvider();

    @Override
    public @Nullable DeclarativeInlayHintsCollector createCollector(PsiFile file, Editor editor) {
        VirtualFile virtualFile = file.getVirtualFile();
        if (virtualFile == null || virtualFile instanceof VirtualFileWindow) {
            return null;
        }
        return new Collector(editor);
    }

    @Override
    public Language getLanguage() {
        return Language.ANY;
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public LocalizeValue getName() {
        return LocalizeValue.of("LSP-based inlays"); // doesn't show up in UI
    }

    @Override
    public LocalizeValue getDescription() {
        return LocalizeValue.empty();
    }

    @Override
    public LocalizeValue getPreviewFileText() {
        return LocalizeValue.empty();
    }

    @Override
    public InlayGroup getGroup() {
        return InlayGroup.OTHER_GROUP;
    }

    private static final class Collector implements DeclarativeInlayHintsCollector.OwnBypassCollector {
        private final Editor myEditor;

        private Collector(Editor editor) {
            myEditor = editor;
        }

        @Override
        public void collectHintsForFile(PsiFile file, DeclarativeInlayTreeSink sink) {
            VirtualFile virtualFile = file.getVirtualFile();
            if (virtualFile == null || virtualFile instanceof VirtualFileWindow) {
                return;
            }
            // Reading the caches also triggers the server requests when they are stale; the responses
            // re-run this pass via LspInlayApplier.scheduleRefresh.
            for (LspInlayItem item : LspInlayApplier.getInstance(file.getProject()).collectInlays(virtualFile)) {
                item.buildPresentation(myEditor, sink);
            }
        }
    }
}

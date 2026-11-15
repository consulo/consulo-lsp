package com.intellij.platform.lsp.impl.features.inlayHint;

import com.intellij.platform.lsp.api.LspClientDescriptor;
import com.intellij.platform.lsp.api.customization.LspInlayHintCustomizer;
import com.intellij.platform.lsp.api.customization.LspInlayHintSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspCachedHighlighting;
import com.intellij.platform.lsp.impl.features.inlayCommon.LspInlayItem;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.codeEditor.Editor;
import consulo.fileEditor.FileEditorManager;
import consulo.language.editor.inlay.DeclarativeInlayPosition;
import consulo.language.editor.inlay.DeclarativeInlayTreeSink;
import consulo.language.editor.inlay.DeclarativePresentationTreeBuilder;
import consulo.language.editor.inlay.HintFormat;
import consulo.language.editor.inlay.InlayActionData;
import consulo.language.editor.inlay.InlayActionPayload;
import consulo.navigation.OpenFileDescriptor;
import consulo.navigation.OpenFileDescriptorFactory;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintLabelPart;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The hints are built with the declarative inlay hints tree builder ({@link HintFormat#DEFAULT} has the rounded background). A clickable label part carries an {@link InlayActionData}
 * handled by {@link LspInlayHintNavigationHandler}.
 */
public final class LspInlayHintRenderingKt {
    private static final int MAX_ALLOWED_INLAY_HINT_LENGTH = 100;
    private static final int MIN_ALLOWED_INLAY_HINT_LENGTH = 1;

    private LspInlayHintRenderingKt() {
    }

    /**
     * Reads the cached inlay hints for {@code virtualFile} across all clients that have it open, as {@link LspInlayItem}s.
     * <p>
     * Calling this also triggers a server request when the cache is stale (see {@link LspInlayHintsCache}).
     * Must be called from a background thread.
     * <p>
     * The global inlay hints switch is checked by the declarative inlay hints pass, which does not run
     * the providers while it is off.
     */
    @RequiredReadAction
    public static List<LspInlayItem> collectInlayHintItems(Project project, VirtualFile virtualFile) {
        Collection<LspClientImpl> clients = LspClientManagerImpl.getInstanceImpl(project).getClientsWithThisFileOpen(virtualFile);
        List<LspInlayItem> result = new ArrayList<>();
        for (LspClientImpl client : clients) {
            LspInlayHintCustomizer customizer = client.getDescriptor().getLspCustomization().getInlayHintCustomizer();
            if (!(customizer instanceof LspInlayHintSupport support)) {
                continue;
            }
            int raw = support.getMaxInlayHintChars();
            int maxChars = Math.max(MIN_ALLOWED_INLAY_HINT_LENGTH, Math.min(raw, MAX_ALLOWED_INLAY_HINT_LENGTH));
            for (LspCachedHighlighting<InlayHint> it : client.getHighlightingCacheRegistry().getInlayHintsCache().getHighlightings(virtualFile)) {
                if (support.shouldDisplayInlayHint(virtualFile, it.getHighlightingInfo())) {
                    result.add(new LspInlayHintItem(client.getDescriptor(), it.getHighlightingInfo(), it.getTextRange().getStartOffset(), maxChars));
                }
            }
        }
        return result;
    }

    private static final class LspInlayHintItem implements LspInlayItem {
        private final LspClientDescriptor myDescriptor;
        private final InlayHint myInlayHint;
        private final int myOffset;
        private final int myMaxChars;
        private final Object myIdentity;

        private LspInlayHintItem(LspClientDescriptor descriptor, InlayHint inlayHint, int offset, int maxChars) {
            myDescriptor = descriptor;
            myInlayHint = inlayHint;
            myOffset = offset;
            myMaxChars = maxChars;
            myIdentity = new LspInlayHintIdentity(descriptor, inlayHint.getLabel(), maxChars);
        }

        @Override
        public int getOffset() {
            return myOffset;
        }

        @Override
        public Object getIdentity() {
            return myIdentity;
        }

        @Override
        public void buildPresentation(Editor editor, DeclarativeInlayTreeSink sink) {
            List<Fragment> fragments = buildInlayPresentation(myInlayHint, myMaxChars);
            if (fragments.isEmpty()) {
                return;
            }
            sink.addPresentation(
                new DeclarativeInlayPosition.InlineInlayPosition(myOffset, false),
                null,
                null,
                HintFormat.DEFAULT,
                builder -> {
                    for (Fragment fragment : fragments) {
                        fragment.addTo(builder);
                    }
                }
            );
        }
    }

    /**
     * The rendered identity of an inlay hint: everything {@link #buildInlayPresentation} reads, and nothing else.
     * <p>
     * Deliberately excludes the text range / {@link InlayHint#getPosition()}: an edit that only shifts a hint's offset moves the
     * existing inlay automatically, so it must still match (and be reused) rather than be recreated. {@link Either} and
     * {@link InlayHintLabelPart} both have value-based {@code equals}, so two structurally equal labels compare equal.
     */
    private static final class LspInlayHintIdentity {
        private final LspClientDescriptor myDescriptor;
        private final Either<String, List<InlayHintLabelPart>> myLabel;
        private final int myMaxChars;

        private LspInlayHintIdentity(LspClientDescriptor descriptor, Either<String, List<InlayHintLabelPart>> label, int maxChars) {
            myDescriptor = descriptor;
            myLabel = label;
            myMaxChars = maxChars;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return this == o
                || o instanceof LspInlayHintIdentity that
                && myDescriptor.equals(that.myDescriptor)
                && Objects.equals(myLabel, that.myLabel)
                && myMaxChars == that.myMaxChars;
        }

        @Override
        public int hashCode() {
            return Objects.hash(myDescriptor, myLabel, myMaxChars);
        }
    }

    /**
     * One text piece of the declarative presentation, optionally clickable.
     */
    private record Fragment(String text, @Nullable InlayActionData actionData) {
        void addTo(DeclarativePresentationTreeBuilder builder) {
            if (actionData != null) {
                builder.text(text, actionData);
            }
            else {
                builder.text(text);
            }
        }
    }

    private static List<Fragment> buildInlayPresentation(InlayHint inlayHint, int maxChars) {
        Either<String, List<InlayHintLabelPart>> label = inlayHint.getLabel();
        String ellipsis = "...";
        List<Fragment> result = new ArrayList<>();
        // If the label consists of parts, compose an interactive presentation per part with truncation by total length
        List<InlayHintLabelPart> parts = label.isRight() ? label.getRight() : null;
        if (parts != null) {
            int currentInlayHintLength = 0;

            for (InlayHintLabelPart part : parts) {
                String value = part.getValue();
                currentInlayHintLength += value.length();
                if (currentInlayHintLength > maxChars) {
                    // Do not add this part; append ellipsis to indicate truncation and stop
                    result.add(new Fragment(ellipsis, null));
                    break;
                }
                if (value.isEmpty()) {
                    continue;
                }

                // Make clickable if location is present
                Location location = part.getLocation();
                InlayActionData actionData = null;
                if (location != null) {
                    actionData = new InlayActionData(
                        new InlayActionPayload.StringInlayActionPayload(LspInlayHintNavigationHandler.encodeLocation(location)),
                        LspInlayHintNavigationHandler.HANDLER_ID
                    );
                }
                result.add(new Fragment(value, actionData));
            }

            return result;
        }

        // If the label consists of a single text part, truncate it by total length
        String hintText = label.isLeft() && label.getLeft() != null ? label.getLeft() : "";
        String shownText = hintText.length() > maxChars ? hintText.substring(0, maxChars) + ellipsis : hintText;
        if (!shownText.isEmpty()) {
            result.add(new Fragment(shownText, null));
        }
        return result;
    }

    @RequiredUIAccess
    static void navigateTo(LspClientDescriptor descriptor, Project project, String targetUri, @Nullable Range targetRange) {
        VirtualFile targetFile = descriptor.findFileByUri(targetUri);
        if (targetFile == null) {
            return;
        }

        FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);
        OpenFileDescriptor openDescriptor = OpenFileDescriptorFactory.getInstance(project).newBuilder(targetFile).build();
        Editor editor = fileEditorManager.openTextEditor(openDescriptor, true);
        // runs on the EDT, so it may touch the editor directly
        if (editor != null && targetRange != null) {
            Integer offset = Lsp4jUtilKt.getOffsetInDocument(editor.getDocument(), targetRange.getStart());
            if (offset != null) {
                editor.getCaretModel().moveToOffset(offset);
            }
        }
    }
}

package com.intellij.platform.lsp.impl.features.codeLens;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.customization.LspCodeLensSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspCachedHighlighting;
import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.dumb.DumbAware;
import consulo.codeEditor.Editor;
import consulo.document.FileDocumentManager;
import consulo.document.util.TextRange;
import consulo.language.editor.codeVision.ClickableTextCodeVisionEntry;
import consulo.language.editor.codeVision.CodeVisionAnchorKind;
import consulo.language.editor.codeVision.CodeVisionEntry;
import consulo.language.editor.codeVision.CodeVisionRelativeOrdering;
import consulo.language.editor.codeVision.DaemonBoundCodeVisionProvider;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.language.psi.PsiFile;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.event.ComponentEvent;
import consulo.ui.image.Image;
import consulo.ui.event.details.ModifiedInputDetails;
import consulo.ui.event.details.MouseInputDetails;
import consulo.ui.ex.awtUnsafe.TargetAWT;
import consulo.util.lang.Pair;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.Command;
import org.jspecify.annotations.Nullable;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The lenses are provided by a {@link DaemonBoundCodeVisionProvider}, computed by the code vision highlighting pass in a
 * background read action. The click handler receives a {@link ComponentEvent}, converted back to an AWT {@link MouseEvent}
 * for {@link LspCodeLensSupport#codeLensClicked}.
 */
@ExtensionImpl
public class LspCodeVisionProvider implements DaemonBoundCodeVisionProvider, DumbAware {
    public static final String LSP_CODE_VISION_PROVIDER_ID = "LspCodeVisionProvider";

    @Override
    @RequiredReadAction
    public List<Pair<TextRange, CodeVisionEntry>> computeForEditor(Editor editor, PsiFile file) {
        Project project = editor.getProject();
        if (project == null) {
            return List.of();
        }
        VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (virtualFile == null) {
            return List.of();
        }
        if (virtualFile instanceof VirtualFileWindow) {
            return List.of();
        }

        List<Pair<LspCachedHighlighting<CodeLens>, LspClientImpl>> lenses = new ArrayList<>();
        for (LspClientImpl client : LspClientManagerImpl.getInstanceImpl(project).getClientsWithThisFileOpen(virtualFile)) {
            for (LspCachedHighlighting<CodeLens> it : client.getHighlightingCacheRegistry().getCodeLensCache().getHighlightings(virtualFile)) {
                lenses.add(Pair.create(it, client));
            }
        }

        if (lenses.isEmpty()) {
            return List.of();
        }

        List<Pair<TextRange, CodeVisionEntry>> entries = new ArrayList<>();

        for (Pair<LspCachedHighlighting<CodeLens>, LspClientImpl> pair : lenses) {
            LspCachedHighlighting<CodeLens> cachedLens = pair.getFirst();
            LspClientImpl client = pair.getSecond();
            CodeLens lens = cachedLens.getHighlightingInfo();
            LspCodeLensSupport customizer = (LspCodeLensSupport) client.getDescriptor().getLspCustomization().getCodeLensCustomizer();
            if (!customizer.shouldDisplayCodeLens(virtualFile, lens)) {
                continue;
            }
            Command command = lens.getCommand();
            if (command == null) {
                continue;
            }
            String title = command.getTitle();
            if (title == null) {
                continue;
            }

            ClickableTextCodeVisionEntry.CodeVisionClickHandler handler = (event, ignoredEditor) ->
                customizer.codeLensClicked((LspClient) client, virtualFile, command, toMouseEvent(event));
            CodeLensTitle presentation = CodeLensTitle.parseCodeLensTitle(title);
            Image icon = presentation.getIcon();
            ClickableTextCodeVisionEntry entry = new ClickableTextCodeVisionEntry(
                presentation.getText(),
                getId(),
                handler,
                icon != null ? TargetAWT.to(icon) : null,
                presentation.getText(),
                "",
                List.of()
            );

            entries.add(Pair.create(cachedLens.getTextRange(), entry));
        }

        return entries;
    }

    /**
     * Consulo-only: the AWT mouse event of a click, {@code null} for a click that did not come from the mouse.
     */
    private static @Nullable MouseEvent toMouseEvent(@Nullable ComponentEvent<?> event) {
        if (event == null || !(event.getInputDetails() instanceof MouseInputDetails mouseDetails)) {
            return null;
        }
        java.awt.Component component;
        try {
            component = TargetAWT.to(event.getComponent());
        }
        catch (IllegalArgumentException e) {
            return null;
        }
        if (component == null) {
            return null;
        }
        int button = switch (mouseDetails.getButton()) {
            case LEFT -> MouseEvent.BUTTON1;
            case MIDDLE -> MouseEvent.BUTTON2;
            case RIGHT -> MouseEvent.BUTTON3;
        };
        return new MouseEvent(
            component,
            MouseEvent.MOUSE_RELEASED,
            System.currentTimeMillis(),
            toAwtModifiers(mouseDetails.getModifiers()),
            mouseDetails.getX(),
            mouseDetails.getY(),
            mouseDetails.getXOnScreen(),
            mouseDetails.getYOnScreen(),
            1,
            false,
            button
        );
    }

    private static int toAwtModifiers(Set<ModifiedInputDetails.Modifier> modifiers) {
        int result = 0;
        for (ModifiedInputDetails.Modifier modifier : modifiers) {
            result |= switch (modifier) {
                case ALT -> InputEvent.ALT_DOWN_MASK;
                case CTRL -> InputEvent.CTRL_DOWN_MASK;
                case SHIFT -> InputEvent.SHIFT_DOWN_MASK;
                case META -> InputEvent.META_DOWN_MASK;
                case ALT_GRAPH -> InputEvent.ALT_GRAPH_DOWN_MASK;
            };
        }
        return result;
    }

    @Override
    public CodeVisionAnchorKind getDefaultAnchor() {
        return CodeVisionAnchorKind.Top;
    }

    @Override
    public List<CodeVisionRelativeOrdering> getRelativeOrderings() {
        return List.of();
    }

    @Override
    public String getId() {
        return LSP_CODE_VISION_PROVIDER_ID;
    }

    @Override
    public LocalizeValue getName() {
        return LspLocalize.codelensLspcodevisionproviderName();
    }
}

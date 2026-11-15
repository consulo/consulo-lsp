package com.intellij.platform.lsp.impl.features.inlayHintColor;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.features.highlightingCommon.LspCachedHighlighting;
import com.intellij.platform.lsp.impl.features.inlayCommon.LspInlayItem;
import consulo.annotation.access.RequiredReadAction;
import consulo.codeEditor.Editor;
import consulo.codeEditor.EditorColors;
import consulo.language.editor.inlay.DeclarativeInlayPosition;
import consulo.language.editor.inlay.DeclarativeInlayTreeSink;
import consulo.language.editor.inlay.HintColorKind;
import consulo.language.editor.inlay.HintFormat;
import consulo.project.Project;
import consulo.ui.color.ColorValue;
import consulo.ui.color.RGBColor;
import consulo.ui.image.Image;
import consulo.ui.image.ImageEffects;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.Color;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Consulo: upstream renders the swatch as an IntelliJ {@code InlayPresentation} (a scaled {@code ColorIcon} with
 * {@code lineCentered}/{@code inset}/{@code withTooltip}); here it is a declarative inlay with an icon drawn by
 * {@link ImageEffects#canvas} and the tooltip of the declarative sink.
 */
public final class LspColorInlayRenderingKt {
    // 12 is the standard size for inlay icons
    private static final int ICON_SIZE = 12;
    private static final int ICON_ARC = 4;

    private LspColorInlayRenderingKt() {
    }

    /**
     * Reads the cached document colors for {@code virtualFile} across all clients that have it open, as {@link LspInlayItem}s,
     * dropping out-of-range colors.
     * <p>
     * Calling this also triggers a server request when the cache is stale (see {@link LspDocumentColorCache}).
     * Must be called from a background thread.
     */
    @RequiredReadAction
    public static List<LspInlayItem> collectColorInlayItems(Project project, VirtualFile virtualFile) {
        List<LspInlayItem> result = new ArrayList<>();
        for (LspClientImpl client : LspClientManagerImpl.getInstanceImpl(project).getClientsWithThisFileOpen(virtualFile)) {
            for (LspCachedHighlighting<Color> cached : client.getHighlightingCacheRegistry().getDocumentColorCache().getHighlightings(virtualFile)) {
                int r = (int) Math.round(cached.getHighlightingInfo().getRed() * 255);
                int g = (int) Math.round(cached.getHighlightingInfo().getGreen() * 255);
                int b = (int) Math.round(cached.getHighlightingInfo().getBlue() * 255);
                int a = (int) Math.round(cached.getHighlightingInfo().getAlpha() * 255);
                if (!inRgbRange(r) || !inRgbRange(g) || !inRgbRange(b) || !inRgbRange(a)) {
                    continue; // invalid color
                }

                result.add(new LspColorInlayItem(cached.getTextRange().getStartOffset(), new LspColorRgba(r, g, b, a)));
            }
        }
        return result;
    }

    private static boolean inRgbRange(int value) {
        return value >= 0 && value <= 255;
    }

    private static final class LspColorInlayItem implements LspInlayItem {
        private final int myOffset;
        private final LspColorRgba myRgba;

        private LspColorInlayItem(int offset, LspColorRgba rgba) {
            myOffset = offset;
            myRgba = rgba;
        }

        @Override
        public int getOffset() {
            return myOffset;
        }

        @Override
        public Object getIdentity() {
            return myRgba;
        }

        @Override
        public void buildPresentation(Editor editor, DeclarativeInlayTreeSink sink) {
            buildColorPresentation(editor, sink, myOffset, myRgba);
        }
    }

    /**
     * The 0..255 components that actually determine the rendered swatch (and serve as its diff identity).
     */
    private record LspColorRgba(int red, int green, int blue, int alpha) {
    }

    private static void buildColorPresentation(Editor editor, DeclarativeInlayTreeSink sink, int offset, LspColorRgba rgba) {
        @Nullable ColorValue borderColor = editor.getColorsScheme().getColor(EditorColors.INDENT_GUIDE_COLOR);
        RGBColor color = new RGBColor(rgba.red(), rgba.green(), rgba.blue(), rgba.alpha() / 255f);
        Image icon = ImageEffects.canvas(ICON_SIZE, ICON_SIZE, ctx -> {
            ctx.setFillStyle(color);
            ctx.beginPath();
            ctx.roundRect(0, 0, ICON_SIZE, ICON_SIZE, ICON_ARC);
            ctx.fill();
            if (borderColor != null) {
                ctx.setStrokeStyle(borderColor);
                ctx.beginPath();
                ctx.roundRect(0, 0, ICON_SIZE, ICON_SIZE, ICON_ARC);
                ctx.stroke();
            }
        });
        sink.addPresentation(
            new DeclarativeInlayPosition.InlineInlayPosition(offset, false),
            null,
            getColorTooltip(rgba),
            HintFormat.DEFAULT.withColorKind(HintColorKind.TextWithoutBackground),
            builder -> builder.icon(icon)
        );
    }

    private static String getColorTooltip(LspColorRgba rgba) {
        int r = rgba.red();
        int g = rgba.green();
        int b = rgba.blue();
        int a = rgba.alpha();
        String slashAndAlfaPercentage;
        if (a == 255) {
            slashAndAlfaPercentage = "";
        }
        else if (a == 0) {
            slashAndAlfaPercentage = " / 0";
        }
        else {
            slashAndAlfaPercentage = " / " + Math.round(a * 100.0 / 255.0) + "%";
        }

        StringBuilder hex = new StringBuilder();
        hex.append("#");
        hex.append(toHex(r));
        hex.append(toHex(g));
        hex.append(toHex(b));
        if (a != 255) {
            hex.append(toHex(a));
        }

        return "rgb(" + r + " " + g + " " + b + slashAndAlfaPercentage + ")\n" + hex;
    }

    private static String toHex(int value) {
        String s = Integer.toString(value, 16);
        return s.length() < 2 ? "0" + s : s;
    }
}

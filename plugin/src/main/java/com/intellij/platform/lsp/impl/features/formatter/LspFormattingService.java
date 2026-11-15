package com.intellij.platform.lsp.impl.features.formatter;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.customization.LspFormattingCustomizer;
import com.intellij.platform.lsp.api.customization.LspFormattingSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspDocument;
import com.intellij.platform.lsp.impl.LspDocumentRange;
import com.intellij.platform.lsp.impl.LspResponseMappingKt;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import com.intellij.platform.lsp.impl.LspServerNotificationsHandlerImpl;
import com.intellij.platform.lsp.localize.LspLocalize;
import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.ReadAction;
import consulo.document.Document;
import consulo.document.util.TextRange;
import consulo.language.codeStyle.CodeStyleSettings;
import consulo.language.codeStyle.FormattingContext;
import consulo.language.codeStyle.FormattingModelBuilder;
import consulo.language.editor.refactoring.ImportOptimizer;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.language.psi.PsiFile;
import consulo.localize.LocalizeValue;
import consulo.lsp.impl.compat.AsyncDocumentFormattingService;
import consulo.lsp.impl.compat.AsyncFormattingRequest;
import consulo.project.ui.notification.NotificationGroup;
import consulo.util.lang.StringUtil;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@link AsyncDocumentFormattingService} is built on top of the
 * {@link consulo.language.codeStyle.ExternalFormatProcessor} extension point, see its Javadoc.
 */
@ExtensionImpl(id = "LspFormattingService")
public class LspFormattingService extends AsyncDocumentFormattingService {
    @Override
    protected LocalizeValue getName() {
        return LspLocalize.lspBasedFormatter();
    }

    @Override
    protected NotificationGroup getNotificationGroup() {
        return LspServerNotificationsHandlerImpl.SHOW_MESSAGE_GROUP;
    }

    @Override
    public Set<Feature> getFeatures() {
        return Set.of(Feature.FORMAT_FRAGMENTS, Feature.OPTIMIZE_IMPORTS);
    }

    // No need to save the document, LSP servers work fine with unsaved content
    @Override
    protected void prepareForFormatting(Document document, FormattingContext formattingContext) {
    }

    @Override
    @RequiredReadAction
    public boolean canFormat(PsiFile psiFile) {
        return canFormat(psiFile, new Feature[0]);
    }

    @Override
    @RequiredReadAction
    public boolean canFormat(PsiFile psiFile, Feature... features) {
        LspFormattingGoal goal = LspFormattingGoal.getFormattingGoal(features);
        if (goal == null) {
            return false;
        }
        LspClient lspClient = switch (goal) {
            case FullFileFormatting -> findClientToFormatThisFile(psiFile, true);
            case RangeFormatting -> findClientToFormatThisFile(psiFile, false);
            case OptimizeImports -> LspImportOptimizer.findClientToOptimizeImports(psiFile);
        };
        return lspClient != null;
    }

    @RequiredReadAction
    private static @Nullable LspClientImpl findClientToFormatThisFile(PsiFile psiFile, boolean isFullFileFormatting) {
        if (psiFile.getProject().isDefault()) {
            return null;
        }
        VirtualFile file = extractValidFile(psiFile);
        if (file == null) {
            return null;
        }

        return LspClientManagerImpl.getInstanceImpl(psiFile.getProject()).findRunningClient(lspClient ->
            lspClient.getDescriptor().isSupportedFile(file) && canClientFormatFile(lspClient, psiFile, file, isFullFileFormatting)
        );
    }

    @RequiredReadAction
    private static boolean canClientFormatFile(LspClientImpl lspClient, PsiFile psiFile, VirtualFile file, boolean isFullFileFormatting) {
        if (isFullFileFormatting && !LspServerCapabilitiesKt.hasFullFileFormattingCapability(lspClient)) {
            return false;
        }
        if (!isFullFileFormatting && !LspServerCapabilitiesKt.hasRangeFormattingCapability(lspClient)) {
            return false;
        }
        LspFormattingCustomizer customizer = lspClient.getDescriptor().getLspCustomization().getFormattingCustomizer();
        if (!(customizer instanceof LspFormattingSupport formattingSupport)) {
            return false;
        }
        FormattingModelBuilder fmb = FormattingModelBuilder.forContext(psiFile);
        boolean ideCanFormatThisFileItself = fmb != null;
        boolean serverExplicitlyWantsToFormatThisFile =
            LspServerCapabilitiesKt.doesServerExplicitlyWantToFormatThisFile(lspClient, file, isFullFileFormatting);

        return formattingSupport.shouldFormatThisFileExclusivelyByServer(file,
            ideCanFormatThisFileItself,
            serverExplicitlyWantsToFormatThisFile);
    }

    // It's safe to unconditionally return `LspImportOptimizer` because all callers of `getImportOptimizers()`
    // immediately call `ImportOptimizer.supports()`, so `LspImportOptimizer.supports()` will take care of replying `true` or `false` correctly.
    @Override
    public Set<ImportOptimizer> getImportOptimizers(PsiFile file) {
        return Set.of(new LspImportOptimizer());
    }

    private static @Nullable VirtualFile extractValidFile(PsiFile psiFile) {
        VirtualFile virtualFile = psiFile.getVirtualFile();
        return virtualFile != null && !(virtualFile instanceof VirtualFileWindow) ? virtualFile : null;
    }

    @Override
    @RequiredReadAction
    protected @Nullable FormattingTask createFormattingTask(AsyncFormattingRequest formattingRequest) {
        VirtualFile file = formattingRequest.getContext().getContainingFile().getVirtualFile();
        if (file == null) {
            return null;
        }
        boolean isFullFileFormatting = isFullFileFormatting(formattingRequest);
        LspClientImpl lspClient = findClientToFormatThisFile(formattingRequest.getContext().getContainingFile(), isFullFileFormatting);
        if (lspClient == null) {
            return null;
        }
        return isFullFileFormatting
            ? new LspFullFileFormattingTask(lspClient, file, formattingRequest)
            : new LspRangeFormattingTask(lspClient, file, formattingRequest);
    }

    private static boolean isFullFileFormatting(AsyncFormattingRequest formattingRequest) {
        List<TextRange> ranges = formattingRequest.getFormattingRanges();
        if (ranges.isEmpty()) {
            return false;
        }

        String text = formattingRequest.getDocumentText();
        return ranges.size() == 1 && ranges.get(0).getStartOffset() == 0 && ranges.get(0).getEndOffset() == text.length();
    }

    private enum LspFormattingGoal {
        FullFileFormatting,
        RangeFormatting,
        OptimizeImports;

        static @Nullable LspFormattingGoal getFormattingGoal(Feature[] features) {
            if (features.length == 0) {
                return FullFileFormatting;
            }
            if (features.length > 1) {
                return null; // the only possible combination is AD_HOC + FRAGMENTS, but AD_HOC is not supported
            }
            if (features[0] == Feature.FORMAT_FRAGMENTS) {
                return RangeFormatting;
            }
            if (features[0] == Feature.OPTIMIZE_IMPORTS) {
                return OptimizeImports;
            }
            return null;
        }
    }

    private abstract static class LspFormattingTask implements FormattingTask {
        protected final LspClientImpl myLspClient;
        protected final VirtualFile myFile;
        protected final AsyncFormattingRequest myFormattingRequest;

        protected LspFormattingTask(LspClientImpl lspClient, VirtualFile file, AsyncFormattingRequest formattingRequest) {
            myLspClient = lspClient;
            myFile = file;
            myFormattingRequest = formattingRequest;
        }

        @Override
        public boolean isRunUnderProgress() {
            return true;
        }

        @Override
        public boolean cancel() {
            return true;
        }

        protected FormattingOptions createFormattingOptions() {
            CodeStyleSettings codeStyleSettings = myFormattingRequest.getContext().getCodeStyleSettings();
            FormattingOptions formattingOptions = new FormattingOptions();
            formattingOptions.setTabSize(codeStyleSettings.getIndentSize(myFile.getFileType()));
            formattingOptions.setInsertSpaces(!codeStyleSettings.useTabCharacter(myFile.getFileType()));
            return formattingOptions;
        }

        protected abstract @Nullable List<TextEdit> fetchTextEdits();

        @Override
        public void run() {
            List<TextEdit> textEdits = fetchTextEdits();
            if (textEdits == null || textEdits.isEmpty()) {
                myFormattingRequest.onTextReady(null);
                return;
            }
            // a document for a background thread can't be created through the public API,
            // so the edits are applied to the text (applyTextEditsToText)
            String result = applyTextEditsToText(myFormattingRequest.getDocumentText(), textEdits);
            myFormattingRequest.onTextReady(result);
        }
    }

    private static final class LspFullFileFormattingTask extends LspFormattingTask {
        private LspFullFileFormattingTask(LspClientImpl lspClient, VirtualFile file, AsyncFormattingRequest formattingRequest) {
            super(lspClient, file, formattingRequest);
        }

        @Override
        protected @Nullable List<TextEdit> fetchTextEdits() {
            FormattingOptions formattingOptions = createFormattingOptions();
            List<LspDocument> lspDocuments = myLspClient.getDocumentMapping().getDocumentsInFileSync(myFile);
            List<TextEdit> results = new ArrayList<>();
            for (LspDocument lspDocument : lspDocuments) {
                DocumentFormattingParams params = new DocumentFormattingParams(lspDocument.getId(), formattingOptions);
                List<? extends TextEdit> edits = myLspClient.sendRequestSync(server -> server.getTextDocumentService().formatting(params));
                if (edits != null) {
                    for (TextEdit edit : edits) {
                        results.add(LspResponseMappingKt.mapTextEdit(lspDocument, edit));
                    }
                }
            }
            return results.isEmpty() ? null : results;
        }
    }

    private static final class LspRangeFormattingTask extends LspFormattingTask {
        private LspRangeFormattingTask(LspClientImpl lspClient, VirtualFile file, AsyncFormattingRequest formattingRequest) {
            super(lspClient, file, formattingRequest);
        }

        @Override
        protected List<TextEdit> fetchTextEdits() {
            FormattingOptions formattingOptions = createFormattingOptions();
            Document document = ReadAction.compute(() -> myFormattingRequest.getContext().getContainingFile().getViewProvider().getDocument());

            List<TextEdit> results = new ArrayList<>();
            if (document == null) {
                return results;
            }
            for (TextRange textRange : myFormattingRequest.getFormattingRanges()) {
                List<LspDocumentRange> lspDocuments = ReadAction.compute(() -> {
                    Range range = Lsp4jUtilKt.getLsp4jRange(document, textRange.getStartOffset(), textRange.getLength());
                    return myLspClient.getDocumentMapping().getDocumentRangesSync(myFile, document, range);
                });
                for (LspDocumentRange lspDocumentRange : lspDocuments) {
                    LspDocument lspDocument = lspDocumentRange.getDocument();
                    DocumentRangeFormattingParams params =
                        new DocumentRangeFormattingParams(lspDocument.getId(), formattingOptions, lspDocumentRange.getRange());
                    List<? extends TextEdit> edits =
                        myLspClient.sendRequestSync(server -> server.getTextDocumentService().rangeFormatting(params));
                    if (edits != null) {
                        for (TextEdit edit : edits) {
                            results.add(LspResponseMappingKt.mapTextEdit(lspDocument, edit));
                        }
                    }
                }
            }
            return results;
        }
    }

    /**
     * Consulo only: {@link Lsp4jUtilKt#applyTextEdits} on a text instead of a {@link Document}, with the same position-to-offset
     * conversion as {@link Lsp4jUtilKt#getOffsetInDocument} (the text has {@code \n} line separators only, like a document).
     *
     * @return the text with all edits applied, or {@code null} if some edit's range is outside the text
     */
    private static @Nullable String applyTextEditsToText(String text, List<TextEdit> textEdits) {
        List<Integer> lineStarts = new ArrayList<>();
        lineStarts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lineStarts.add(i + 1);
            }
        }

        // The edits must be applied from bottom to top, edits that have the same position in the reversed order,
        // so the offsets in the original text stay valid for the edits that are not applied yet.
        List<TextEdit> sorted = new ArrayList<>(textEdits);
        sorted.sort((edit1, edit2) -> {
            int lineDiff = edit1.getRange().getStart().getLine() - edit2.getRange().getStart().getLine();
            return lineDiff != 0 ? lineDiff : edit1.getRange().getStart().getCharacter() - edit2.getRange().getStart().getCharacter();
        });
        StringBuilder result = new StringBuilder(text);
        for (int i = sorted.size() - 1; i >= 0; i--) {
            TextEdit textEdit = sorted.get(i);
            Integer startOffset = getOffsetInText(text, lineStarts, textEdit.getRange().getStart());
            Integer endOffset = getOffsetInText(text, lineStarts, textEdit.getRange().getEnd());
            if (startOffset == null || endOffset == null || startOffset > endOffset) {
                return null;
            }
            result.replace(startOffset, endOffset, StringUtil.convertLineSeparators(textEdit.getNewText()));
        }
        return result.toString();
    }

    private static @Nullable Integer getOffsetInText(String text, List<Integer> lineStarts, Position position) {
        int lineCount = lineStarts.size();
        int line = position.getLine();
        int character = position.getCharacter();
        if (line == lineCount && character == 0) {
            return text.length();
        }
        if (line < 0 || line >= lineCount || character < 0) {
            return null;
        }

        int lineStartOffset = lineStarts.get(line);
        if (line + 1 < lineCount && character > 0) {
            int nextLineStartOffset = lineStarts.get(line + 1);
            character = Math.min(character, nextLineStartOffset - 1 - lineStartOffset);
        }
        else if (line + 1 == lineCount && character > 0) {
            character = Math.min(character, text.length() - lineStartOffset);
        }

        int offset = lineStartOffset + character;
        return offset <= text.length() ? offset : null;
    }
}

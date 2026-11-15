package com.intellij.platform.lsp.impl.features.highlighting;

import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsCustomizer;
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsSupport;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.dumb.DumbAware;
import consulo.codeEditor.Editor;
import consulo.language.editor.highlight.usage.HighlightUsagesHandlerBase;
import consulo.language.editor.highlight.usage.HighlightUsagesHandlerFactory;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.DocumentHighlightKind;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@ExtensionImpl
public class LspHighlightUsagesHandlerFactory implements HighlightUsagesHandlerFactory<PsiElement>, DumbAware {
    @Override
    @RequiredReadAction
    public @Nullable HighlightUsagesHandlerBase<PsiElement> createHighlightUsagesHandler(Editor editor, PsiFile psiFile) {
        VirtualFile virtualFile = psiFile.getVirtualFile();
        if (virtualFile == null || virtualFile instanceof VirtualFileWindow) {
            return null;
        }
        List<LspClientImpl> clients = new ArrayList<>();
        for (LspClientImpl client : LspClientManagerImpl.getInstanceImpl(psiFile.getProject()).getClientsWithThisFileOpen(virtualFile)) {
            if (shouldProceedWithClient(client, psiFile)) {
                clients.add(client);
            }
        }

        if (clients.isEmpty()) {
            return null;
        }

        return new LspHighlightUsagesHandler(editor, psiFile, virtualFile, clients);
    }

    private static boolean shouldProceedWithClient(LspClientImpl client, PsiFile file) {
        LspDocumentHighlightsCustomizer customizer = client.getDescriptor().getLspCustomization().getDocumentHighlightsCustomizer();
        VirtualFile virtualFile = file.getVirtualFile();
        return customizer instanceof LspDocumentHighlightsSupport support
            && virtualFile != null
            && LspServerCapabilitiesKt.supportsDocumentHighlights(client, virtualFile)
            && support.shouldAskServerForDocumentHighlights(file);
    }

    private static final class LspHighlightUsagesHandler extends HighlightUsagesHandlerBase<PsiElement> implements DumbAware {
        private final VirtualFile myVirtualFile;
        private final List<LspClientImpl> myClients;

        private LspHighlightUsagesHandler(Editor editor, PsiFile psiFile, VirtualFile virtualFile, List<LspClientImpl> clients) {
            super(editor, psiFile);
            myVirtualFile = virtualFile;
            myClients = clients;
        }

        @Override
        public List<PsiElement> getTargets() {
            return List.of(myFile);
        }

        @Override
        protected void selectTargets(List<PsiElement> targets, Consumer<List<PsiElement>> selectionConsumer) {
            selectionConsumer.accept(new ArrayList<>(targets));
        }

        @Override
        public void computeUsages(List<PsiElement> targets) {
            int offset = myEditor.getCaretModel().getOffset();

            for (LspClientImpl client : myClients) {
                List<TextRangeAndHighlightKind> highlights = client.getRequestExecutor().getDocumentHighlightsCaching(myVirtualFile, offset);
                if (highlights == null) {
                    continue;
                }
                for (TextRangeAndHighlightKind it : highlights) {
                    if (it.getKind() == DocumentHighlightKind.Write) {
                        myWriteUsages.add(it.getTextRange());
                    }
                    else {
                        myReadUsages.add(it.getTextRange());
                    }
                }
            }
        }
    }
}

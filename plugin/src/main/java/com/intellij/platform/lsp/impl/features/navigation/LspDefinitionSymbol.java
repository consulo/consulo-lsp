package com.intellij.platform.lsp.impl.features.navigation;

import com.intellij.platform.lsp.util.Lsp4jUtilKt;
import consulo.annotation.access.RequiredReadAction;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.document.util.TextRange;
import consulo.language.impl.psi.FakePsiElement;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.localize.LocalizeValue;
import consulo.navigation.Navigatable;
import consulo.navigation.OpenFileDescriptorFactory;
import consulo.project.Project;
import consulo.ui.image.Image;
import consulo.util.lang.StringUtil;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.VirtualFileManager;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * A declaration reported by
 * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_definition">textDocument/definition</a> or
 * <a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_typeDefinition">textDocument/typeDefinition</a>.
 * <p>
 * Consulo: a fake PSI element in the target file (see {@link LspNavigationSymbol}); upstream {@code computePresentation()}
 * became the {@code ItemPresentation} of the element, used by the "Choose Declaration" popup.
 *
 * @param targetSelectionRange the range to put the caret at, {@code null} to navigate to the start of the file
 */
public class LspDefinitionSymbol extends FakePsiElement implements LspNavigationSymbol {
    private static final int TARGET_PRESENTABLE_TEXT_MAX_LENGTH = 20;

    private final Project myProject;
    private final VirtualFile myTargetFile;
    private final @Nullable Range myTargetSelectionRange;

    public LspDefinitionSymbol(Project project, VirtualFile targetFile, @Nullable Range targetSelectionRange) {
        myProject = project;
        myTargetFile = targetFile;
        myTargetSelectionRange = targetSelectionRange;
    }

    public VirtualFile getTargetFile() {
        return myTargetFile;
    }

    public @Nullable Range getTargetSelectionRange() {
        return myTargetSelectionRange;
    }

    public List<Navigatable> getNavigationTargets(Project project) {
        return List.of(new LspDefinitionNavigationTarget(project, myTargetFile, myTargetSelectionRange));
    }

    @Override
    public Project getProject() {
        return myProject;
    }

    @Override
    public PsiManager getManager() {
        return PsiManager.getInstance(myProject);
    }

    @Override
    @RequiredReadAction
    public @Nullable PsiElement getParent() {
        return myTargetFile.isValid() ? PsiManager.getInstance(myProject).findFile(myTargetFile) : null;
    }

    @Override
    public @Nullable PsiFile getContainingFile() {
        return myTargetFile.isValid() ? PsiManager.getInstance(myProject).findFile(myTargetFile) : null;
    }

    @Override
    public boolean isValid() {
        return myTargetFile.isValid();
    }

    @Override
    public boolean isPhysical() {
        return true;
    }

    @Override
    public int getTextOffset() {
        Document document = FileDocumentManager.getInstance().getDocument(myTargetFile);
        if (document == null || myTargetSelectionRange == null) {
            return 0;
        }
        Integer offset = Lsp4jUtilKt.getOffsetInDocument(document, myTargetSelectionRange.getStart());
        return offset != null ? offset : 0;
    }

    @Override
    public void navigate(boolean requestFocus) {
        new LspDefinitionNavigationTarget(myProject, myTargetFile, myTargetSelectionRange).navigate(requestFocus);
    }

    @Override
    public boolean canNavigate() {
        return myTargetFile.isValid();
    }

    @Override
    public boolean canNavigateToSource() {
        return canNavigate();
    }

    @Override
    public String getName() {
        return getTargetPresentableText(myTargetFile, myTargetSelectionRange);
    }

    @Override
    public LocalizeValue getPresentableText() {
        return LocalizeValue.of(getTargetPresentableText(myTargetFile, myTargetSelectionRange));
    }

    @Override
    public @Nullable String getLocationString() {
        return getLocationText(myTargetFile, myTargetSelectionRange != null ? myTargetSelectionRange.getStart() : null);
    }

    @Override
    public @Nullable Image getIcon() {
        return VirtualFileManager.getInstance().getFileIcon(myTargetFile, myProject, 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LspDefinitionSymbol that)) {
            return false;
        }
        return myTargetFile.equals(that.myTargetFile) && Objects.equals(myTargetSelectionRange, that.myTargetSelectionRange);
    }

    @Override
    public int hashCode() {
        return Objects.hash(myTargetFile, myTargetSelectionRange);
    }

    private static final class LspDefinitionNavigationTarget implements Navigatable {
        private final Project myProject;
        private final VirtualFile myTargetFile;
        private final @Nullable Range myTargetSelectionRange;

        private LspDefinitionNavigationTarget(Project project, VirtualFile targetFile, @Nullable Range targetSelectionRange) {
            myProject = project;
            myTargetFile = targetFile;
            myTargetSelectionRange = targetSelectionRange;
        }

        private @Nullable Navigatable navigationRequest() {
            Document document = FileDocumentManager.getInstance().getDocument(myTargetFile);
            if (document == null) {
                return null;
            }
            Integer offset = myTargetSelectionRange != null
                ? Lsp4jUtilKt.getOffsetInDocument(document, myTargetSelectionRange.getStart())
                : null;
            return OpenFileDescriptorFactory.getInstance(myProject)
                .newBuilder(myTargetFile)
                .offset(offset != null ? offset : 0)
                .build();
        }

        @Override
        public void navigate(boolean requestFocus) {
            Navigatable request = navigationRequest();
            if (request != null) {
                request.navigate(requestFocus);
            }
        }

        @Override
        public boolean canNavigate() {
            return myTargetFile.isValid();
        }

        @Override
        public boolean canNavigateToSource() {
            return canNavigate();
        }
    }

    private static String getTargetPresentableText(VirtualFile targetFile, @Nullable Range targetSelectionRange) {
        if (targetSelectionRange == null) {
            return targetFile.getName();
        }
        Document document = FileDocumentManager.getInstance().getDocument(targetFile);
        if (document == null) {
            return targetFile.getName();
        }
        TextRange textRange = Lsp4jUtilKt.getRangeInDocument(document, targetSelectionRange);
        if (textRange == null) {
            return targetFile.getName();
        }
        if (textRange.getLength() > 0) {
            return StringUtil.shortenTextWithEllipsis(document.getText(textRange), TARGET_PRESENTABLE_TEXT_MAX_LENGTH, 0);
        }
        if (document.getTextLength() <= textRange.getStartOffset()) {
            return targetFile.getName(); // end of file
        }
        int endOffset = Math.min(document.getTextLength(), textRange.getStartOffset() + TARGET_PRESENTABLE_TEXT_MAX_LENGTH);
        return document.getText(new TextRange(textRange.getStartOffset(), endOffset)) + "…";
    }

    private static String getLocationText(VirtualFile targetFile, @Nullable Position position) {
        return position == null
            ? targetFile.getName()
            : targetFile.getName() + ":" + (position.getLine() + 1) + ":" + (position.getCharacter() + 1);
    }
}

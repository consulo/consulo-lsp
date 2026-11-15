package com.intellij.platform.lsp.impl.features.navigation;

import consulo.annotation.access.RequiredReadAction;
import consulo.language.impl.psi.FakePsiElement;
import consulo.language.psi.PsiDirectory;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.localize.LocalizeValue;
import consulo.navigation.Navigatable;
import consulo.navigation.OpenFileDescriptorFactory;
import consulo.project.Project;
import consulo.ui.image.Image;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.VirtualFileManager;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * The file or directory a document link points at, as opposed to {@link LspOpenBrowserNavigatableSymbol} for one that points at a URL.
 * <p>
 * Carries no Ctrl+hover label: see {@link LspNavigationSymbol}.
 * <p>
 * Consulo: a fake PSI element that navigates itself (see {@link LspNavigationSymbol}); upstream {@code computePresentation()}
 * ({@code computeLinkPresentation}) became the {@code ItemPresentation} of the element. {@code createPointer()} is dropped.
 */
public class LspPathDocumentLinkSymbol extends FakePsiElement implements LspNavigationSymbol {
    private final Project myProject;
    private final VirtualFile myTargetFileOrDir;
    private final List<VirtualFile> myRoots;

    public LspPathDocumentLinkSymbol(Project project, VirtualFile targetFileOrDir, List<VirtualFile> roots) {
        myProject = project;
        myTargetFileOrDir = targetFileOrDir;
        myRoots = roots;
    }

    public List<Navigatable> getNavigationTargets(Project project) {
        return List.of(new LspDocumentLinkNavigationTarget(project, myTargetFileOrDir));
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
        if (!myTargetFileOrDir.isValid()) {
            return null;
        }
        return myTargetFileOrDir.isDirectory()
            ? PsiManager.getInstance(myProject).findDirectory(myTargetFileOrDir)
            : PsiManager.getInstance(myProject).findFile(myTargetFileOrDir);
    }

    @Override
    public @Nullable PsiFile getContainingFile() {
        return myTargetFileOrDir.isValid() && !myTargetFileOrDir.isDirectory()
            ? PsiManager.getInstance(myProject).findFile(myTargetFileOrDir)
            : null;
    }

    @Override
    public boolean isValid() {
        return myTargetFileOrDir.isValid();
    }

    @Override
    public void navigate(boolean requestFocus) {
        new LspDocumentLinkNavigationTarget(myProject, myTargetFileOrDir).navigate(requestFocus);
    }

    @Override
    public boolean canNavigate() {
        return myTargetFileOrDir.isValid();
    }

    @Override
    public boolean canNavigateToSource() {
        return canNavigate() && !myTargetFileOrDir.isDirectory();
    }

    @Override
    public String getName() {
        return myTargetFileOrDir.getName();
    }

    @Override
    public LocalizeValue getPresentableText() {
        return LocalizeValue.of(myTargetFileOrDir.getName());
    }

    @Override
    public @Nullable String getLocationString() {
        return LspNavigationUtilKt.getPresentablePath(myTargetFileOrDir, myRoots);
    }

    @Override
    public @Nullable Image getIcon() {
        return VirtualFileManager.getInstance().getFileIcon(myTargetFileOrDir, myProject, 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LspPathDocumentLinkSymbol that)) {
            return false;
        }
        return myTargetFileOrDir.equals(that.myTargetFileOrDir) && myRoots.equals(that.myRoots);
    }

    @Override
    public int hashCode() {
        return Objects.hash(myTargetFileOrDir, myRoots);
    }

    private static final class LspDocumentLinkNavigationTarget implements Navigatable {
        private final Project myProject;
        private final VirtualFile myTargetFileOrDir;

        private LspDocumentLinkNavigationTarget(Project project, VirtualFile targetFileOrDir) {
            myProject = project;
            myTargetFileOrDir = targetFileOrDir;
        }

        private @Nullable Navigatable navigationRequest() {
            if (myTargetFileOrDir.isDirectory()) {
                PsiDirectory psiDirectory = PsiManager.getInstance(myProject).findDirectory(myTargetFileOrDir);
                return psiDirectory;
            }
            return OpenFileDescriptorFactory.getInstance(myProject).newBuilder(myTargetFileOrDir).offset(0).build();
        }

        @Override
        public void navigate(boolean requestFocus) {
            Navigatable request = navigationRequest();
            if (request != null && request.canNavigate()) {
                request.navigate(requestFocus);
            }
        }

        @Override
        public boolean canNavigate() {
            return myTargetFileOrDir.isValid();
        }

        @Override
        public boolean canNavigateToSource() {
            return canNavigate() && !myTargetFileOrDir.isDirectory();
        }
    }
}

package com.intellij.platform.lsp.impl.features.navigation;

import consulo.annotation.access.RequiredReadAction;
import consulo.language.impl.psi.FakePsiElement;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.localize.LocalizeValue;
import consulo.navigation.Navigatable;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.Project;
import consulo.ui.image.Image;
import consulo.webBrowser.BrowserUtil;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * The web page a document link points at.
 * <p>
 * Consulo: a fake PSI element that navigates itself (see {@link LspNavigationSymbol}), so it needs the {@code project} upstream
 * receives in {@code getNavigationTargets(project)}. {@code createPointer()} is dropped; the presentation is the
 * {@code ItemPresentation} of the element. {@link #computeDocumentationHint()} is kept, but Consulo's Ctrl+hover does not ask
 * for it.
 *
 * @param tooltip {@link com.intellij.platform.lsp.impl.features.highlighting.LspDocumentLink#getTooltip()}, the same words the
 *                link carries where it is rendered in the editor
 */
public class LspOpenBrowserNavigatableSymbol extends FakePsiElement implements LspNavigationSymbol {
    private final Project myProject;
    private final String myUrl;
    private final @Nullable String myTooltip;

    public LspOpenBrowserNavigatableSymbol(Project project, String url, @Nullable String tooltip) {
        myProject = project;
        myUrl = url;
        myTooltip = tooltip;
    }

    public @Nullable String computeDocumentationHint() {
        return myTooltip;
    }

    public List<Navigatable> getNavigationTargets(Project project) {
        return List.of(new Navigatable() {
            @Override
            public boolean canNavigate() {
                return true;
            }

            @Override
            public void navigate(boolean requestFocus) {
                BrowserUtil.browse(myUrl);
            }
        });
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
        return null;
    }

    @Override
    public @Nullable PsiFile getContainingFile() {
        return null;
    }

    @Override
    public boolean isValid() {
        return !myProject.isDisposed();
    }

    @Override
    public boolean canNavigate() {
        return true;
    }

    @Override
    public boolean canNavigateToSource() {
        return false;
    }

    @Override
    public void navigate(boolean requestFocus) {
        BrowserUtil.browse(myUrl);
    }

    @Override
    public String getName() {
        return myUrl;
    }

    @Override
    public LocalizeValue getPresentableText() {
        return LocalizeValue.of(myUrl);
    }

    @Override
    public @Nullable String getLocationString() {
        return null;
    }

    @Override
    public @Nullable Image getIcon() {
        return PlatformIconGroup.generalWeb();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LspOpenBrowserNavigatableSymbol that)) {
            return false;
        }
        return myUrl.equals(that.myUrl) && Objects.equals(myTooltip, that.myTooltip);
    }

    @Override
    public int hashCode() {
        return Objects.hash(myUrl, myTooltip);
    }
}

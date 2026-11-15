package com.intellij.platform.lsp.impl.features.usages;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.annotation.access.RequiredReadAction;
import consulo.component.util.Iconable;
import consulo.language.impl.psi.FakePsiElement;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.image.Image;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.VirtualFileManager;
import org.eclipse.lsp4j.Position;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

/**
 * Also a symbol so that {@link com.intellij.platform.lsp.impl.features.navigation.LspImplicitReferenceProvider}
 * can resolve a reference to it when the caret is on a declaration.
 * The symbol has no navigation targets, so the 'Go To Declaration or Usages' action falls back to 'Show Usages'.
 * It must not implement {@code NavigationTarget} or {@code NavigatableSymbol}: that would turn the fallback back into a self-navigation.
 * <p>
 * Consulo: IntelliJ's {@code SearchTarget}/{@code Symbol} are replaced with a fake PSI element, the element whose usages
 * {@link LspUsageSearcher} finds. Its name is the upstream {@code usageHandler} presentable text: the header of the
 * usage view.
 */
public class LspSearchTarget extends FakePsiElement {
    private final Collection<LspClient> myLspClients;
    private final VirtualFile myFile;
    private final Position myPosition;

    // It would be great to have something more meaningful here. For example, guess a word at caret.
    // We can get the text range of the declaration from the server
    // (by passing `ReferenceContext.includeDeclaration = true` to the server).
    // But we'll get the response much later than the Platform asks for `targetPresentableText`.
    private final String myFileNameAndPosition;

    private final String myPresentableName;

    public LspSearchTarget(Collection<LspClient> lspClients, VirtualFile file, Position position) {
        myLspClients = lspClients;
        myFile = file;
        myPosition = position;
        myFileNameAndPosition = file.getName() + ":" + (position.getLine() + 1) + ":" + (position.getCharacter() + 1);
        myPresentableName = lspClients.size() == 1 ? lspClients.iterator().next().getDescriptor().getPresentableName() : "LSP";
    }

    public Collection<LspClient> getLspClients() {
        return myLspClients;
    }

    public VirtualFile getFile() {
        return myFile;
    }

    public Position getPosition() {
        return myPosition;
    }

    // Used to render the header in the 'Show Usages' popup
    // and also the tab header in the 'Find Usages' tool window
    public LocalizeValue getUsageHandlerPresentableText() {
        return LspLocalize.zeroFindReferences1(myPresentableName, myFileNameAndPosition);
    }

    @Override
    public Project getProject() {
        return myLspClients.iterator().next().getProject();
    }

    @Override
    public PsiManager getManager() {
        return PsiManager.getInstance(getProject());
    }

    @Override
    @RequiredReadAction
    public @Nullable PsiElement getParent() {
        return myFile.isValid() ? PsiManager.getInstance(getProject()).findFile(myFile) : null;
    }

    @Override
    public @Nullable PsiFile getContainingFile() {
        return myFile.isValid() ? PsiManager.getInstance(getProject()).findFile(myFile) : null;
    }

    @Override
    public boolean isValid() {
        return myFile.isValid();
    }

    @Override
    public boolean canNavigate() {
        return false;
    }

    @Override
    public void navigate(boolean requestFocus) {
    }

    @Override
    public String getName() {
        return getUsageHandlerPresentableText().get();
    }

    // Used to render the search target in the result tree in the 'Find Usages' tool window
    @Override
    public LocalizeValue getPresentableText() {
        return LocalizeValue.of(myFileNameAndPosition);
    }

    @Override
    public @Nullable Image getIcon() {
        return VirtualFileManager.getInstance().getFileIcon(myFile, getProject(), Iconable.ICON_FLAG_VISIBILITY);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        LspSearchTarget that = (LspSearchTarget) other;

        if (!myFile.equals(that.myFile)) {
            return false;
        }
        if (!myPosition.equals(that.myPosition)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = myFile.hashCode();
        result = 31 * result + myPosition.hashCode();
        return result;
    }
}

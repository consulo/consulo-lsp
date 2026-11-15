package com.intellij.platform.lsp.impl.features.hierarchy.call;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.features.hierarchy.LspFakePsiElement;
import com.intellij.platform.lsp.impl.features.hierarchy.LspHierarchyNodeDescriptor;
import consulo.annotation.access.RequiredReadAction;
import consulo.language.editor.hierarchy.HierarchyKind;
import consulo.language.editor.hierarchy.HierarchyModel;
import consulo.language.editor.hierarchy.HierarchyRequest;
import consulo.language.editor.hierarchy.HierarchyTreeStructure;
import consulo.language.editor.hierarchy.HierarchyViewType;
import consulo.language.editor.hierarchy.StandardHierarchyKinds;
import consulo.language.editor.hierarchy.StandardHierarchyViewTypes;
import consulo.language.psi.PsiElement;
import consulo.project.Project;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The {@link HierarchyModel} of the LSP call hierarchy; the hierarchy browser itself is owned by the platform:
 * <ul>
 *     <li>{@link #getViewTypes()} - the caller and callee trees;</li>
 *     <li>{@link #createTreeStructure(HierarchyRequest)} - the tree structure of a view;</li>
 *     <li>{@link #isScopeSupported()} is {@code false} (the default) - there is no scope action;</li>
 *     <li>the sorting (alphabetical or by index) and the element of a descriptor are handled by the platform browser;</li>
 *     <li>the tree root is visible and the auto-expansion is not limited.</li>
 * </ul>
 */
public class LspCallHierarchyBrowser implements HierarchyModel<PsiElement> {
    private final Project myProject;
    private final PsiElement myElement;
    private final LspClientImpl myClient;

    public LspCallHierarchyBrowser(Project project, PsiElement element, LspClientImpl client) {
        myProject = project;
        myElement = element;
        myClient = client;
    }

    @Override
    public HierarchyKind getKind() {
        return StandardHierarchyKinds.CALL;
    }

    @Override
    public PsiElement getTarget() {
        return myElement;
    }

    @Override
    public List<HierarchyViewType> getViewTypes() {
        return List.of(getCallerType(), getCalleeType());
    }

    /**
     * The browser opens the caller view.
     */
    @Override
    @RequiredReadAction
    public HierarchyViewType getDefaultViewType() {
        return getCallerType();
    }

    @Override
    @RequiredReadAction
    public boolean isApplicableElement(PsiElement element) {
        return element instanceof LspFakePsiElement;
    }

    @Override
    @RequiredReadAction
    public @Nullable HierarchyTreeStructure createTreeStructure(HierarchyRequest<PsiElement> request) {
        return createHierarchyTreeStructure(request.getViewType(), request.getTarget());
    }

    private @Nullable HierarchyTreeStructure createHierarchyTreeStructure(HierarchyViewType type, PsiElement psiElement) {
        if (type.equals(getCallerType())) {
            return new LspCallerTreeStructure(myProject, getBaseDescriptor(psiElement));
        }
        if (type.equals(getCalleeType())) {
            return new LspCalleeTreeStructure(myProject, getBaseDescriptor(psiElement));
        }
        throw new IllegalStateException("unexpected type: " + type);
    }

    private LspHierarchyNodeDescriptor getBaseDescriptor(PsiElement psiElement) {
        return new LspHierarchyNodeDescriptor(myClient, null, psiElement, null, true);
    }

    private static HierarchyViewType getCallerType() {
        return StandardHierarchyViewTypes.CALLER;
    }

    private static HierarchyViewType getCalleeType() {
        return StandardHierarchyViewTypes.CALLEE;
    }
}

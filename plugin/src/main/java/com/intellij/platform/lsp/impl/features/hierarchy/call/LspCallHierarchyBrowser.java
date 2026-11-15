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
 * Consulo: upstream extends {@code CallHierarchyBrowserBase}. Consulo's hierarchy browser is owned by the platform,
 * a language supplies a {@link HierarchyModel} instead, so this class is the model:
 * <ul>
 *     <li>{@code createTrees} (caller and callee trees) - {@link #getViewTypes()};</li>
 *     <li>{@code createHierarchyTreeStructure} - {@link #createTreeStructure(HierarchyRequest)};</li>
 *     <li>{@code prependActions} removed the scope action - {@link #isScopeSupported()} is {@code false} (the default);</li>
 *     <li>{@code getComparator} (alphabetical or by index) and {@code getElementFromDescriptor}
 *     ({@code descriptor.psiElement}) - the same is done by the platform browser;</li>
 *     <li>{@code createHierarchyTree} hid the tree root and limited the auto-expansion
 *     ({@code DefaultTreeUI.AUTO_EXPAND_FILTER}) - not supported by the platform browser, the root is visible.</li>
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
     * Consulo: upstream {@code LspCallHierarchyProvider.browserActivated} switches the browser to the caller view.
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

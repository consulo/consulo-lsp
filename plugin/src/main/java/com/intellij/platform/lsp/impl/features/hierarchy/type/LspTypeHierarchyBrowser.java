package com.intellij.platform.lsp.impl.features.hierarchy.type;

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
 * The {@link HierarchyModel} of the LSP type hierarchy; the hierarchy browser itself is owned by the platform:
 * <ul>
 *     <li>{@link #getViewTypes()} - the supertypes and subtypes trees; the platform binds the shortcut of
 *     {@link StandardHierarchyKinds#TYPE} ("Base on this type"), the root stays visible;</li>
 *     <li>the platform toolbar offers the view types of the model and the alphabetical sorting;</li>
 *     <li>{@link #createTreeStructure(HierarchyRequest)} - the tree structure of a view; there is no class view;</li>
 *     <li>the sorting and the element of a descriptor are handled by the platform browser;</li>
 *     <li>not an interface, not deletable, no qualified name and no legend panel - the defaults of {@link HierarchyModel} and
 *     {@link consulo.language.editor.hierarchy.HierarchyNodeDescriptor}.</li>
 * </ul>
 */
public class LspTypeHierarchyBrowser implements HierarchyModel<PsiElement> {
    private final Project myProject;
    private final PsiElement myElement;
    private final LspClientImpl myClient;

    public LspTypeHierarchyBrowser(Project project, PsiElement element, LspClientImpl client) {
        myProject = project;
        myElement = element;
        myClient = client;
    }

    @Override
    public HierarchyKind getKind() {
        return StandardHierarchyKinds.TYPE;
    }

    @Override
    public PsiElement getTarget() {
        return myElement;
    }

    @Override
    public List<HierarchyViewType> getViewTypes() {
        return List.of(getSupertypesHierarchyType(), getSubtypesHierarchyType());
    }

    /**
     * The browser opens the subtypes view.
     */
    @Override
    @RequiredReadAction
    public HierarchyViewType getDefaultViewType() {
        return getSubtypesHierarchyType();
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
        if (type.equals(getSupertypesHierarchyType())) {
            return new LspSupertypesHierarchyTreeStructure(myProject, getBaseDescriptor(psiElement));
        }
        if (type.equals(getSubtypesHierarchyType())) {
            return new LspSubtypesHierarchyTreeStructure(myProject, getBaseDescriptor(psiElement));
        }
        if (type.equals(StandardHierarchyViewTypes.CLASS)) {
            return null;
        }
        throw new IllegalStateException("unexpected type: " + type);
    }

    private LspHierarchyNodeDescriptor getBaseDescriptor(PsiElement psiElement) {
        return new LspHierarchyNodeDescriptor(myClient, null, psiElement, null, true);
    }

    private static HierarchyViewType getSupertypesHierarchyType() {
        return StandardHierarchyViewTypes.SUPERTYPES;
    }

    private static HierarchyViewType getSubtypesHierarchyType() {
        return StandardHierarchyViewTypes.SUBTYPES;
    }
}

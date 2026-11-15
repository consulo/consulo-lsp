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
 * Consulo: upstream extends {@code TypeHierarchyBrowserBase}. Consulo's hierarchy browser is owned by the platform,
 * a language supplies a {@link HierarchyModel} instead, so this class is the model:
 * <ul>
 *     <li>{@code createTrees} (supertypes and subtypes trees, root hidden, "Base on this type" bound to the type
 *     hierarchy shortcut) - {@link #getViewTypes()}; the platform binds the shortcut of
 *     {@link StandardHierarchyKinds#TYPE}, the root stays visible;</li>
 *     <li>{@code prependActions} (supertypes, subtypes, alphabetical sorting) - the platform toolbar offers the view
 *     types of the model and the sorting;</li>
 *     <li>{@code createHierarchyTreeStructure} - {@link #createTreeStructure(HierarchyRequest)}; the class view
 *     ({@code getTypeHierarchyType()}, no structure upstream) is not offered;</li>
 *     <li>{@code getComparator}, {@code getElementFromDescriptor} - done by the platform browser the same way;</li>
 *     <li>{@code isInterface} = false, {@code canBeDeleted} = false, {@code getQualifiedName} = null,
 *     {@code createLegendPanel} = null - the defaults of {@link HierarchyModel} and
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
     * Consulo: upstream {@code LspTypeHierarchyProvider.browserActivated} switches the browser to the subtypes view.
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

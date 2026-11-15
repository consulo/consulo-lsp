package com.intellij.platform.lsp.impl.features.navigation;

import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.codeEditor.Editor;
import consulo.language.editor.action.TypeDeclarationProvider;
import consulo.language.psi.PsiDocumentManager;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.project.Project;
import consulo.ui.ex.action.IdeActions;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Used for Go To Type Declaration feature backed by the information from an LSP server
 * (<a href="https://microsoft.github.io/language-server-protocol/specification/#textDocument_typeDefinition">textDocument/typeDefinition</a> request).
 * <p>
 * This class relies on the result calculated in {@link LspImplicitReferenceProvider#getImplicitReference}.
 * <p>
 * The {@code symbol} is the target element that {@link LspTypeDeclarationTargetElementUtilExtender} found at the caret.
 * The platform passes a single target element, so all the type definitions are taken from the (cached) reference again.
 */
@ExtensionImpl
public class LspSymbolTypeProvider extends TypeDeclarationProvider {
    @Override
    @RequiredReadAction
    public PsiElement @Nullable [] getSymbolTypeDeclarations(PsiElement symbol, @Nullable Editor editor, int offset) {
        if (!(symbol instanceof LspNavigationSymbol)) {
            return null;
        }
        // When [GotoTypeDeclarationAction] is running, the `LspDefinitionSymbol` is a result of
        // `LspResolvedSymbolReference.resolveReference`, and it already points to the type declaration
        // as a result of the `textDocument/typeDefinition` request
        if (symbol instanceof LspDefinitionSymbol) {
            List<PsiElement> allTypes = editor != null ? findAllTypeDefinitions(editor, offset) : null;
            return allTypes != null && allTypes.contains(symbol) ? allTypes.toArray(PsiElement.EMPTY_ARRAY) : new PsiElement[]{symbol};
        }
        // A document link leads to a file, a directory or a web page, and none of those has a type
        return PsiElement.EMPTY_ARRAY;
    }

    @RequiredReadAction
    private static @Nullable List<PsiElement> findAllTypeDefinitions(Editor editor, int offset) {
        Project project = editor.getProject();
        if (project == null) {
            return null;
        }
        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (psiFile == null) {
            return null;
        }
        LspImplicitReferenceProvider.LspResolvedSymbolReference reference =
            new LspImplicitReferenceProvider().getImplicitReference(psiFile, offset, IdeActions.ACTION_GOTO_TYPE_DECLARATION, false);
        return reference != null ? reference.resolveReference() : null;
    }
}

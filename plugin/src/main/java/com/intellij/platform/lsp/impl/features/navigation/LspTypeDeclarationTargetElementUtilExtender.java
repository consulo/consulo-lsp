package com.intellij.platform.lsp.impl.features.navigation;

import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.codeEditor.Editor;
import consulo.language.editor.TargetElementUtilExtender;
import consulo.language.editor.inject.EditorWindow;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.lsp.impl.compat.ActionContextElement;
import consulo.ui.ex.action.IdeActions;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Consulo-only adapter, no upstream counterpart.
 * <p>
 * Upstream, Go To Type Declaration resolves the implicit reference of {@link LspImplicitReferenceProvider} and passes the resolved
 * {@link LspDefinitionSymbol} to {@link LspSymbolTypeProvider}. Consulo's {@code GotoTypeDeclarationAction} calls the
 * {@code TypeDeclarationProvider} extensions only for a target element found by {@code TargetElementUtil}, and LSP-backed files
 * usually have flat PSI without references. This extender provides the target element when no PSI reference is found:
 * the first {@code textDocument/typeDefinition} result. Like upstream, it runs only while the "Go To Type Declaration" action is
 * performed (the action thread context), so the other target element lookups do not send requests to the server.
 */
@ExtensionImpl
public class LspTypeDeclarationTargetElementUtilExtender implements TargetElementUtilExtender {
    @Override
    @RequiredReadAction
    public @Nullable PsiElement modifyReferenceOrReferencedElement(
        @Nullable PsiElement refElement,
        PsiFile file,
        Editor editor,
        Set<String> flags,
        int offset
    ) {
        if (refElement != null || editor instanceof EditorWindow) {
            return null;
        }
        ActionContextElement context = ActionContextElement.getActionThreadContext();
        if (context == null || !IdeActions.ACTION_GOTO_TYPE_DECLARATION.equals(context.getActionId())) {
            return null;
        }
        LspImplicitReferenceProvider.LspResolvedSymbolReference reference =
            new LspImplicitReferenceProvider().getImplicitReference(file, offset, IdeActions.ACTION_GOTO_TYPE_DECLARATION, false);
        if (reference == null) {
            return null;
        }
        List<PsiElement> results = reference.resolveReference();
        return results.isEmpty() ? null : results.get(0);
    }
}

package com.intellij.platform.lsp.impl.features.parameterInfo;

import com.intellij.platform.lsp.api.customization.LspSignatureHelpDisabled;
import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspClientManagerImpl;
import com.intellij.platform.lsp.impl.LspServerCapabilitiesKt;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.dumb.DumbAware;
import consulo.document.util.TextRange;
import consulo.language.Language;
import consulo.language.editor.completion.lookup.LookupElement;
import consulo.language.editor.parameterInfo.CreateParameterInfoContext;
import consulo.language.editor.parameterInfo.ParameterInfoContext;
import consulo.language.editor.parameterInfo.ParameterInfoHandler;
import consulo.language.editor.parameterInfo.ParameterInfoUIContext;
import consulo.language.editor.parameterInfo.SignatureBuilder;
import consulo.language.editor.parameterInfo.SignatureStyle;
import consulo.language.editor.parameterInfo.UpdateParameterInfoContext;
import consulo.language.file.inject.VirtualFileWindow;
import consulo.language.psi.PsiElement;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.jsonrpc.messages.Tuple;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link #getLanguage()} returns {@link Language#ANY}, so the handler works in any file.
 */
@ExtensionImpl(id = "LspParameterInfoHandler")
public class LspParameterInfoHandler implements ParameterInfoHandler<PsiElement, LspParameterInfoContext>, DumbAware {
    @Override
    public Language getLanguage() {
        return Language.ANY;
    }

    // The lookup functions are not supported
    @Override
    public boolean couldShowInLookup() {
        return false;
    }

    @Override
    public @Nullable Object[] getParametersForLookup(LookupElement item, ParameterInfoContext context) {
        // The platform method is non-null; never called because couldShowInLookup() is false
        return new Object[0];
    }

    @Override
    public @Nullable PsiElement findElementForParameterInfo(CreateParameterInfoContext context) {
        if (context.getFile().getProject().isDefault()) {
            return null;
        }

        VirtualFile virtualFile = getVirtualFile(context);
        if (virtualFile == null) {
            return null;
        }
        for (LspClientImpl client : LspClientManagerImpl.getInstanceImpl(context.getFile().getProject()).getClientsWithThisFileOpen(virtualFile)) {
            if (client.getDescriptor().getLspCustomization().getSignatureHelpCustomizer() instanceof LspSignatureHelpDisabled) {
                continue;
            }
            if (!LspServerCapabilitiesKt.supportsSignatureHelp(client, virtualFile)) {
                continue;
            }
            SignatureHelp signatureHelp = client.getRequestExecutor().getSignatureHelp(virtualFile, context.getOffset());
            if (signatureHelp == null) {
                continue;
            }
            if (!signatureHelp.getSignatures().isEmpty()) {
                context.setItemsToShow(new Object[]{new LspParameterInfoContext(signatureHelp, client)});
                return context.getFile();
            }
        }
        return null;
    }

    @Override
    public @Nullable PsiElement findElementForUpdatingParameterInfo(UpdateParameterInfoContext context) {
        Object[] objectsToView = context.getObjectsToView();
        return objectsToView != null && objectsToView.length > 0 ? context.getFile() : null;
    }

    @Override
    public void showParameterInfo(PsiElement element, CreateParameterInfoContext context) {
        context.showHint(element, context.getOffset(), this);
    }

    @Override
    public void updateParameterInfo(PsiElement parameterOwner, UpdateParameterInfoContext context) {
        if (context.getParameterOwner() != parameterOwner) {
            context.removeHint();
            return;
        }
        Object[] objectsToView = context.getObjectsToView();
        if (objectsToView == null || objectsToView.length == 0 || !(objectsToView[0] instanceof LspParameterInfoContext storedContext)) {
            return;
        }
        VirtualFile virtualFile = getVirtualFile(context);
        if (virtualFile == null) {
            return;
        }
        SignatureHelp signatureHelp = storedContext.getClient().getRequestExecutor().getSignatureHelp(virtualFile, context.getOffset());
        if (signatureHelp == null || signatureHelp.getSignatures().isEmpty()) {
            context.removeHint();
            return;
        }
        // `updateUI()` must render the same response that the current parameter index is calculated from
        storedContext.setSignatureHelp(signatureHelp);
        context.setCurrentParameter(getActiveParameterIndex(signatureHelp));
    }

    @Override
    public void updateUI(@Nullable LspParameterInfoContext infoContext, ParameterInfoUIContext context) {
        if (infoContext == null) {
            context.setUIComponentEnabled(false);
            return;
        }

        SignatureInformation signature = getActiveSignature(infoContext.getSignatureHelp());
        String text = signature != null ? signature.getLabel() : null;
        if (signature == null || text == null || text.isEmpty()) {
            context.setUIComponentEnabled(false);
            return;
        }

        // `currentParameterIndex` is -1 if no parameter is active
        List<@Nullable TextRange> parameterRanges = getParameterRanges(signature);
        int currentParameterIndex = context.getCurrentParameterIndex();
        TextRange highlightRange = currentParameterIndex >= 0 && currentParameterIndex < parameterRanges.size()
            ? parameterRanges.get(currentParameterIndex)
            : null;

        // The signature builder keeps the label as is (the legacy presentation splits it at commas)
        SignatureBuilder builder = context.signature();
        if (highlightRange == null || highlightRange.isEmpty()) {
            builder.text(text);
        }
        else {
            if (highlightRange.getStartOffset() > 0) {
                builder.text(text.substring(0, highlightRange.getStartOffset()));
            }
            builder.parameter(highlightRange.substring(text), SignatureStyle.HIGHLIGHT);
            if (highlightRange.getEndOffset() < text.length()) {
                builder.text(text.substring(highlightRange.getEndOffset()));
            }
        }
        builder.apply();
    }

    private static @Nullable VirtualFile getVirtualFile(ParameterInfoContext context) {
        VirtualFile virtualFile = context.getFile().getVirtualFile();
        if (virtualFile == null) {
            return null;
        }
        return virtualFile instanceof VirtualFileWindow virtualFileWindow ? virtualFileWindow.getDelegate() : virtualFile;
    }

    private List<@Nullable TextRange> getParameterRanges(SignatureInformation signature) {
        String signatureLabel = signature.getLabel();
        int searchFrom = 0;
        List<ParameterInformation> parameters = signature.getParameters();
        List<@Nullable TextRange> result = new ArrayList<>();
        if (parameters == null) {
            return result;
        }
        for (ParameterInformation parameter : parameters) {
            TextRange range;
            if (parameter.getLabel().isLeft()) {
                String text = parameter.getLabel().getLeft();
                // The spec only requires the string to be a substring of the signature label.
                // Searching after the previous parameter helps when the same text is found earlier in the label.
                int start = signatureLabel.indexOf(text, searchFrom);
                if (start < 0) {
                    start = signatureLabel.indexOf(text);
                }
                range = !text.isEmpty() && start >= 0 ? new TextRange(start, start + text.length()) : null;
            }
            else {
                Tuple.Two<Integer, Integer> offsets = parameter.getLabel().getRight();
                int start = offsets.getFirst();
                int end = offsets.getSecond();
                range = start >= 0 && start <= end && end <= signatureLabel.length() ? new TextRange(start, end) : null;
            }
            if (range != null) {
                searchFrom = range.getEndOffset();
            }
            result.add(range);
        }
        return result;
    }

    private @Nullable SignatureInformation getActiveSignature(SignatureHelp signatureHelp) {
        List<SignatureInformation> signatures = signatureHelp.getSignatures();
        Integer activeSignature = signatureHelp.getActiveSignature();
        int index = activeSignature != null ? activeSignature : 0;
        if (index >= 0 && index < signatures.size()) {
            return signatures.get(index);
        }
        return signatures.isEmpty() ? null : signatures.get(0);
    }

    /**
     * @return the index of the active parameter in the active signature, or -1 if no parameter is active
     */
    private int getActiveParameterIndex(SignatureHelp signatureHelp) {
        SignatureInformation signature = getActiveSignature(signatureHelp);
        if (signature == null) {
            return -1;
        }
        int parameterCount = signature.getParameters() != null ? signature.getParameters().size() : 0;
        if (parameterCount == 0) {
            return -1;
        }

        // Per spec v3.18, `null` means that no parameter is active, while an omitted `SignatureInformation.activeParameter` falls back
        // to `SignatureHelp.activeParameter`, and an omitted `SignatureHelp.activeParameter` defaults to 0.
        // lsp4j deserializes both an omitted value and `null` as `null`, so `null` in `SignatureInformation.activeParameter` falls back
        // to `SignatureHelp.activeParameter`, and `null` there means that no parameter is active.
        Integer signatureActiveParameter = signature.getActiveParameter();
        if (signatureActiveParameter != null) {
            // The spec doesn't specify the out-of-range case for `SignatureInformation.activeParameter`
            return signatureActiveParameter >= 0 && signatureActiveParameter < parameterCount ? signatureActiveParameter : -1;
        }

        Integer index = signatureHelp.getActiveParameter();
        if (index == null) {
            return -1;
        }
        // Per spec, if `SignatureHelp.activeParameter` lies outside the range of the active signature parameters, it defaults to 0
        return index >= 0 && index < parameterCount ? index : 0;
    }
}

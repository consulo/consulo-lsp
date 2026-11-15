package com.intellij.platform.lsp.impl.features.inlayCommon;

import consulo.annotation.component.ExtensionImpl;
import consulo.application.dumb.DumbAware;
import consulo.language.Language;
import consulo.language.editor.inlay.DeclarativeInlayHintsProviderFactory;
import consulo.language.editor.inlay.InlayProviderInfo;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * {@link #getSupportedLanguages()} is empty, so the provider is not listed in the inlay hints settings.
 */
@ExtensionImpl
public class LspInlayHintsProviderFactory implements DeclarativeInlayHintsProviderFactory, DumbAware {
    @Override
    public List<InlayProviderInfo> getProvidersForLanguage(Language language) {
        return List.of(createProviderInfo());
    }

    @Override
    public Set<Language> getSupportedLanguages() {
        return Set.of();
    }

    @Override
    public @Nullable InlayProviderInfo getProviderInfo(Language language, String providerId) {
        return LspInlayHintsProvider.PROVIDER_ID.equals(providerId) ? createProviderInfo() : null;
    }

    private static InlayProviderInfo createProviderInfo() {
        LspInlayHintsProvider provider = LspInlayHintsProvider.INSTANCE;
        return new InlayProviderInfo(provider, provider.getId(), provider.getOptions(), provider.isEnabledByDefault(), provider.getName());
    }
}

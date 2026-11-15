package com.intellij.platform.lsp.impl.features.codeLens;

import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.localize.LocalizeValue;

/**
 * Consulo: there is no {@code config.codeVisionGroupSettingProvider} extension point. Consulo's code vision settings derive the
 * group from {@link LspCodeVisionProvider#getGroupId()} and show {@link LspCodeVisionProvider#getName()}; a group description
 * is not shown. The class is kept (not registered) for parity with upstream.
 */
public class LspCodeVisionGroupSettingProvider {
    public String getGroupId() {
        return LspCodeVisionProvider.LSP_CODE_VISION_PROVIDER_ID;
    }

    public LocalizeValue getGroupName() {
        return LspLocalize.codelensLspcodevisionproviderName();
    }

    public LocalizeValue getDescription() {
        return LspLocalize.codelensLspcodevisionproviderDescription();
    }
}

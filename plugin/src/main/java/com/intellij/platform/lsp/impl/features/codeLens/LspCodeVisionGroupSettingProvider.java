package com.intellij.platform.lsp.impl.features.codeLens;

import com.intellij.platform.lsp.localize.LspLocalize;
import consulo.localize.LocalizeValue;

/**
 * Not registered: there is no {@code config.codeVisionGroupSettingProvider} extension point. The code vision settings derive the
 * group from {@link LspCodeVisionProvider#getGroupId()} and show {@link LspCodeVisionProvider#getName()}; a group description
 * is not shown.
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

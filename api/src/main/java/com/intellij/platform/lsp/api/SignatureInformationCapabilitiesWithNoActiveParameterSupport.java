// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import org.eclipse.lsp4j.SignatureInformationCapabilities;
import org.eclipse.lsp4j.jsonrpc.util.ToStringBuilder;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Adds the {@code noActiveParameterSupport} property (LSP 3.18), which the {@code lsp4j} library v1.0.0 doesn't support yet.
 * <p>
 * TODO: Remove this class once the {@code lsp4j} lib is updated and supports {@code noActiveParameterSupport} out of the box.
 * (The class will become not compilable with the next {@code lsp4j} library update.)
 */
public class SignatureInformationCapabilitiesWithNoActiveParameterSupport extends SignatureInformationCapabilities {
    // the field name is the JSON property name (Gson), so it has no `my` prefix
    private @Nullable Boolean noActiveParameterSupport = null;

    public @Nullable Boolean getNoActiveParameterSupport() {
        return noActiveParameterSupport;
    }

    public void setNoActiveParameterSupport(@Nullable Boolean noActiveParameterSupport) {
        this.noActiveParameterSupport = noActiveParameterSupport;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return super.equals(other) &&
            other instanceof SignatureInformationCapabilitiesWithNoActiveParameterSupport that &&
            Objects.equals(noActiveParameterSupport, that.noActiveParameterSupport);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + Objects.hashCode(noActiveParameterSupport);
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
            .add("documentationFormat", getDocumentationFormat())
            .add("parameterInformation", getParameterInformation())
            .add("activeParameterSupport", getActiveParameterSupport())
            .add("noActiveParameterSupport", noActiveParameterSupport)
            .toString();
    }
}

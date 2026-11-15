// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.util;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;

public final class Lsp4jConvertersKt {
    private Lsp4jConvertersKt() {
    }

    public static LocationLink toLocationLink(Location location) {
        return new LocationLink(location.getUri(), location.getRange(), location.getRange());
    }
}

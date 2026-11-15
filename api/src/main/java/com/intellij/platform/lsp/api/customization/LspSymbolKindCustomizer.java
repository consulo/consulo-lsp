// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api.customization;

import consulo.platform.base.icon.PlatformIconGroup;
import consulo.ui.image.Image;
import org.eclipse.lsp4j.SymbolKind;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

public class LspSymbolKindCustomizer {
    private final List<SymbolKind> mySupportedKinds = Arrays.asList(SymbolKind.values());

    /**
     * Compare with {@link LspCompletionSupport#getIcon} for similar, but incompatible icon mapping
     */
    public @Nullable Image getIcon(SymbolKind kind) {
        return switch (kind) {
            case File -> PlatformIconGroup.filetypesAny_type();
            case Module -> PlatformIconGroup.nodesModule();
            case Namespace -> PlatformIconGroup.nodesPackage();
            case Package -> PlatformIconGroup.nodesPackage();
            case Class -> PlatformIconGroup.nodesClass();
            case Method -> PlatformIconGroup.nodesMethod();
            case Property -> PlatformIconGroup.nodesProperty();
            case Field -> PlatformIconGroup.nodesField();
            case Constructor -> PlatformIconGroup.nodesMethod();
            case Enum -> PlatformIconGroup.nodesEnum();
            case Interface -> PlatformIconGroup.nodesInterface();
            case Function -> PlatformIconGroup.nodesFunction();
            case Variable -> PlatformIconGroup.nodesVariable();
            case Constant -> PlatformIconGroup.nodesConstant();
            case String -> PlatformIconGroup.nodesConstant();
            case Number -> PlatformIconGroup.nodesConstant();
            case Boolean -> PlatformIconGroup.nodesConstant();
            case Array -> PlatformIconGroup.nodesVariable();
            case Object -> PlatformIconGroup.nodesClass();
            case Key -> PlatformIconGroup.nodesProperty();
            case Null -> PlatformIconGroup.nodesConstant();
            case EnumMember -> PlatformIconGroup.nodesField();
            case Struct -> PlatformIconGroup.nodesClass();
            case Event -> PlatformIconGroup.nodesMethod();
            case Operator -> PlatformIconGroup.nodesMethod();
            case TypeParameter -> PlatformIconGroup.nodesParameter();
        };
    }

    public List<SymbolKind> getSupportedKinds() {
        return mySupportedKinds;
    }
}

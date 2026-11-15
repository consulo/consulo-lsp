// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import consulo.lsp.compat.BaseProjectDirectories;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;

/**
 * @deprecated Renamed to {@link ProjectWideLspClientDescriptor}
 */
@Deprecated
@SuppressWarnings("deprecation")
public abstract class ProjectWideLspServerDescriptor extends LspServerDescriptor {
    public ProjectWideLspServerDescriptor(Project project, String presentableName) {
        super(project, presentableName, BaseProjectDirectories.getBaseDirectories(project).toArray(VirtualFile.EMPTY_ARRAY));
    }
}

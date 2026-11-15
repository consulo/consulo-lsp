// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import consulo.lsp.compat.BaseProjectDirectories;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;

/**
 * An {@link LspClientDescriptor} that assumes that a single LSP server is going to serve the whole project, regardless of the project structure.
 * So, it uses all {@link BaseProjectDirectories#getBaseDirectories} as LSP server roots.
 */
public abstract class ProjectWideLspClientDescriptor extends LspClientDescriptor {
    public ProjectWideLspClientDescriptor(Project project, String presentableName) {
        super(project, presentableName, BaseProjectDirectories.getBaseDirectories(project).toArray(VirtualFile.EMPTY_ARRAY));
    }
}

// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
/*
 * Copyright 2013-2026 consulo.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package consulo.lsp.compat;

import consulo.application.ReadAction;
import consulo.module.content.ProjectRootManager;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The base directories of a project.
 * <p>
 * The set is computed on each call from the content roots of the project modules.
 */
public final class BaseProjectDirectories {
    private BaseProjectDirectories() {
    }

    /**
     * Return top-level directories which contain files related to the project. Usually the returned set contains a single element, but
     * it may be empty for an empty project, and may contain more than one element if project files are located in different directories.
     * <p>
     * Returns all content roots of modules that aren't located under other content roots.
     */
    public static Set<VirtualFile> getBaseDirectories(Project project) {
        VirtualFile[] contentRoots = ReadAction.compute(() -> ProjectRootManager.getInstance(project).getContentRoots());
        Set<VirtualFile> result = new LinkedHashSet<>();
        for (VirtualFile root : contentRoots) {
            if (!root.isDirectory()) {
                continue;
            }
            boolean nested = false;
            for (VirtualFile other : contentRoots) {
                if (other != root && other.isDirectory() && VirtualFileUtil.isAncestor(other, root, true)) {
                    nested = true;
                    break;
                }
            }
            if (!nested) {
                result.add(root);
            }
        }
        return Collections.unmodifiableSet(result);
    }
}

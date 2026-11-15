// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl.cache;

import consulo.application.progress.ProgressManager;
import consulo.language.psi.PsiManager;
import consulo.project.Project;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Single-slot memoization guarded by {@link PsiManager}'s modification count.
 * <p>
 * Stores at most one {@code (key, value)} pair. A lookup hits the cache when:
 * <ol>
 *  <li>the global PSI modification count hasn't changed since the stored value was put, and</li>
 *  <li>{@code matches} returns {@code true} for the stored key/value against the queried key.</li>
 * </ol>
 * <p>
 * By default {@code matches} is key equality. Override it to implement containment-style lookups
 * (e.g. "cursor offset still inside the cached result's text range").
 */
public final class LspSingleSlotCache<K, V> implements LspCache {
    private final Project myProject;
    private final LspPerFileCache.Matcher<K, V> myMatches;

    private long myLastPsiModificationCount = -1;
    private @Nullable K myLastKey = null;
    private @Nullable V myLastResult = null;

    public LspSingleSlotCache(Project project) {
        this(project, (stored, storedValue, queried) -> Objects.equals(stored, queried));
    }

    public LspSingleSlotCache(Project project, LspPerFileCache.Matcher<K, V> matches) {
        myProject = project;
        myMatches = matches;
    }

    /**
     * Must be called on a background thread.
     */
    public synchronized @Nullable V getOrCompute(K key, Supplier<@Nullable V> compute) {
        ProgressManager.checkCanceled();

        long psiModCount = PsiManager.getInstance(myProject).getModificationTracker().getModificationCount();
        K storedKey = myLastKey;
        V storedValue = myLastResult;
        if (storedKey != null && storedValue != null
            && myLastPsiModificationCount == psiModCount
            && myMatches.matches(storedKey, storedValue, key)) {
            myLastKey = key;
            return storedValue;
        }

        V newResult = compute.get();
        if (newResult == null) {
            return null;
        }
        myLastPsiModificationCount = psiModCount;
        myLastKey = key;
        myLastResult = newResult;
        return newResult;
    }

    @Override
    public synchronized void clearCache() {
        myLastPsiModificationCount = -1;
        myLastKey = null;
        myLastResult = null;
    }
}

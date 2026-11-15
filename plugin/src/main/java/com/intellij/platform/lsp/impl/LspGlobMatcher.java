package com.intellij.platform.lsp.impl;

import consulo.lsp.impl.compat.GlobUtilKt;
import org.eclipse.lsp4j.FileOperationFilter;
import org.eclipse.lsp4j.FileOperationPattern;
import org.eclipse.lsp4j.FileOperationPatternKind;
import org.jspecify.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LspGlobMatcher {
    private final Map<String, PathMatcher> myPatternToPathMatcherCache = new ConcurrentHashMap<>();

    public boolean pathMatches(String path, boolean isDirectory, String globPattern, @Nullable String basePath) {
        String pathToMatch;
        if (basePath == null) {
            pathToMatch = path;
        }
        else if (path.equals(basePath)) {
            pathToMatch = "";
        }
        else if (path.startsWith(basePath + "/")) {
            pathToMatch = path.substring(basePath.length() + 1);
        }
        else {
            return false; // base URI doesn't match this path
        }

        if (isDirectory) {
            // Any directory may contain files that match the glob pattern.
            // Per-file events are not generated, so we need to inform the server about all directory events.
            return true;
        }

        PathMatcher pathMatcher = myPatternToPathMatcherCache.computeIfAbsent(globPattern, GlobUtilKt::getPathMatcher);
        return pathMatcher.matches(Path.of(pathToMatch));
    }

    /**
     * @param path file path as seen by server; to get a file path, use {@link com.intellij.platform.lsp.api.LspClientDescriptor#getFilePath}
     */
    public boolean pathMatchesFileOperationFilter(
        String path,
        String uriScheme,
        boolean isDirectory,
        FileOperationFilter filter
    ) {
        if (filter.getScheme() != null && !filter.getScheme().equalsIgnoreCase(uriScheme)) {
            return false;
        }

        FileOperationPattern pattern = filter.getPattern();
        if (pattern == null) {
            return false;
        }
        String matches = pattern.getMatches();
        if (matches != null) {
            if (FileOperationPatternKind.File.equals(matches)) {
                if (isDirectory) {
                    return false;
                }
            }
            else if (FileOperationPatternKind.Folder.equals(matches)) {
                if (!isDirectory) {
                    return false;
                }
            }
            else {
                return false;
            }
        }

        String globPattern = pattern.getGlob();
        if (globPattern == null) {
            return false;
        }
        boolean ignoreCase = pattern.getOptions() != null && Boolean.TRUE.equals(pattern.getOptions().getIgnoreCase());
        String glob = ignoreCase ? globPattern.toLowerCase(Locale.ROOT) : globPattern;
        String pathToMatch = ignoreCase ? path.toLowerCase(Locale.ROOT) : path;
        PathMatcher pathMatcher = myPatternToPathMatcherCache.computeIfAbsent(glob, GlobUtilKt::getPathMatcher);
        try {
            return pathMatcher.matches(Path.of(pathToMatch));
        }
        catch (InvalidPathException e) {
            return false;
        }
    }
}

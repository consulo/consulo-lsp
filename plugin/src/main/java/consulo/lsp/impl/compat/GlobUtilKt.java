// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package consulo.lsp.impl.compat;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;

/**
 * Port of {@code com.intellij.openapi.util.globUtil.kt} (IntelliJ {@code getPathMatcher}), which has no Consulo equivalent.
 */
public final class GlobUtilKt {
    private GlobUtilKt() {
    }

    public static PathMatcher getPathMatcher(String globPattern) {
        return getPathMatcher(globPattern, true);
    }

    /**
     * This function is similar to {@link FileSystem#getPathMatcher FileSystems.getDefault().getPathMatcher("glob: ...")}
     * (and uses that one internally),
     * but this function handles <a href="https://code.visualstudio.com/docs/editor/glob-patterns">globstars</a> ({@code **})
     * better than the JDK's native glob path matcher implementation.
     * <p>
     * The JDK's implementation of {@code FileSystem.getPathMatcher()} says that
     * the path {@code foo.txt} doesn't match the glob pattern {@code **}{@code /foo.txt},
     * or that the path {@code src/foo.txt} doesn't match the glob pattern {@code src/}{@code **}{@code /foo.txt}.
     * The reason is that the JDK's implementation adds mandatory slash to the regexp, which it uses under the hood.
     * <p>
     * The {@code PathMatcher} returned by this function works with globstars as expected.
     * <p>
     * If the {@code globPattern} is invalid and {@code ignorePatternSyntaxException} is {@code true} then the returned
     * {@code PathMatcher} always returns {@code false} from its {@code matches(...)} method.
     *
     * @param globPattern the passed argument doesn't need to have {@code glob:} prefix (though no difference if it does have this prefix)
     * @throws PatternSyntaxException if {@code ignorePatternSyntaxException} is {@code false} and the {@code globPattern} is invalid
     */
    public static PathMatcher getPathMatcher(String globPattern, boolean ignorePatternSyntaxException) {
        String pattern = globPattern.startsWith("glob:") ? globPattern.substring("glob:".length()) : globPattern;
        List<String> patterns = transformIntoJdkFriendlyGlobs(pattern);

        List<PathMatcher> matchers = new ArrayList<>();
        for (String it : patterns) {
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + it));
            }
            catch (PatternSyntaxException e) {
                if (!ignorePatternSyntaxException) {
                    throw e;
                }
            }
        }

        if (matchers.size() == 1) {
            return matchers.get(0);
        }
        return path -> {
            for (PathMatcher matcher : matchers) {
                if (matcher.matches(path)) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * Transforms the provided {@code globPattern} and/or splits it into several glob patterns as needed for further usage as an argument of the
     * {@link FileSystem#getPathMatcher} function. The transformation is needed to work around the JDK limitations, as described in {@link #getPathMatcher}.
     */
    private static List<String> transformIntoJdkFriendlyGlobs(String globPattern) {
        // Example: glob pattern `**/{foo/**/bar/**/baz,123}` splits into 4 JDK-friendly globs, which together cover all cases:
        // `{**/,}{foo/**/bar/**/baz,123}`
        // `{**/,}{foo/bar/**/baz,123}`
        // `{**/,}{foo/**/bar/baz,123}`
        // `{**/,}{foo/bar/baz,123}`

        // `FileSystem.getPathMatcher()` doesn't support nested groups. Need to make sure we don't introduce such.
        int braceIndex = globPattern.indexOf('{');
        String globBeforeBrace = braceIndex == -1 ? globPattern : globPattern.substring(0, braceIndex);
        String globTail = globPattern.substring(globBeforeBrace.length());
        // We can safely replace `**/` with `{**/,}` only while we are sure we don't introduce nested groups.
        String fixedGlobBeforeBrace = globBeforeBrace.replace("**/", "{**/,}");

        List<String> result = new ArrayList<>();
        result.add(fixedGlobBeforeBrace + globTail);

        int globstarIndex = globTail.indexOf("**/");
        int globstarsInTail = 0;
        while (globstarIndex >= 0) {
            globstarsInTail++;
            // Replace "**/" with "" to handle the case when `**` matches zero path segments.
            result.add(fixedGlobBeforeBrace + globTail.substring(0, globstarIndex) + globTail.substring(globstarIndex + "**/".length()));
            globstarIndex = globTail.indexOf("**/", globstarIndex + 1);
        }

        if (globstarsInTail > 1) {
            result.add(fixedGlobBeforeBrace + globTail.replace("**/", ""));
            // All cases when globstarsInTail <= 2 are covered, but some exotic cases when globstarsInTail > 2 won't work (too many combinations).
        }

        return result;
    }
}

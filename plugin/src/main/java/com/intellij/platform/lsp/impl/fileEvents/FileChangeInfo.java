package com.intellij.platform.lsp.impl.fileEvents;

import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.WatchKind;
import org.jspecify.annotations.Nullable;

public class FileChangeInfo {
    private final String myPath;
    private final String myUri;
    private final boolean myIsDirectory;
    private final FileChangeType myChangeType;

    public FileChangeInfo(String path, String uri, boolean isDirectory, FileChangeType changeType) {
        myPath = path;
        myUri = uri;
        myIsDirectory = isDirectory;
        myChangeType = changeType;
    }

    public String getPath() {
        return myPath;
    }

    public String getUri() {
        return myUri;
    }

    public boolean isDirectory() {
        return myIsDirectory;
    }

    public FileChangeType getChangeType() {
        return myChangeType;
    }

    public boolean doesFileWatcherKindMatchFileChangeType(@Nullable Integer watchKind) {
        // https://microsoft.github.io/language-server-protocol/specification/#fileSystemWatcher
        if (watchKind == null) {
            // null is equivalent to WatchKind.Create | WatchKind.Change | WatchKind.Delete
            return true;
        }

        return switch (myChangeType) {
            case Created -> (watchKind & WatchKind.Create) != 0;
            case Changed -> (watchKind & WatchKind.Change) != 0;
            case Deleted -> (watchKind & WatchKind.Delete) != 0;
        };
    }
}

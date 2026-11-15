package com.intellij.platform.lsp.impl.fileEvents;

import com.intellij.platform.lsp.impl.LspClientImpl;
import com.intellij.platform.lsp.impl.LspDynamicCapabilities;
import consulo.virtualFileSystem.VirtualFile;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.FileSystemWatcher;
import org.eclipse.lsp4j.RelativePattern;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Sends {@code workspace/didChangeWatchedFiles} notifications about file-system events the {@code client} is interested in.
 * <p>
 * The platform listeners (e.g. {@code LspFileListener}) collect VFS events and dispatch them here.
 */
public class LspWatchedFiles {
    private final LspClientImpl myClient;

    public LspWatchedFiles(LspClientImpl client) {
        myClient = client;
    }

    public void processFileEvents(Collection<FileChangeInfo> fileChangeInfos) {
        List<DidChangeWatchedFilesRegistrationOptions> options =
            myClient.getDynamicCapabilities().getCapabilityRegistrationOptions(LspDynamicCapabilities.getDidChangeWatchedFiles());
        List<FileEvent> eventsOfInterest = new ArrayList<>();
        for (FileChangeInfo fileChangeInfo : fileChangeInfos) {
            FileEvent event = getLsp4jFileEvent(fileChangeInfo, options);
            if (event != null) {
                eventsOfInterest.add(event);
            }
        }
        if (!eventsOfInterest.isEmpty()) {
            myClient.sendNotification(it -> it.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(eventsOfInterest)));
        }
    }

    private @Nullable FileEvent getLsp4jFileEvent(FileChangeInfo fileChangeInfo, List<DidChangeWatchedFilesRegistrationOptions> options) {
        for (DidChangeWatchedFilesRegistrationOptions option : options) {
            for (FileSystemWatcher watcher : option.getWatchers()) {
                if (!fileChangeInfo.doesFileWatcherKindMatchFileChangeType(watcher.getKind())) {
                    continue;
                }

                if (watcher.getGlobPattern().isLeft()) {
                    String globPattern = watcher.getGlobPattern().getLeft();
                    if (myClient.getGlobMatcher().pathMatches(fileChangeInfo.getPath(), fileChangeInfo.isDirectory(), globPattern, null)) {
                        return new FileEvent(fileChangeInfo.getUri(), fileChangeInfo.getChangeType());
                    }
                }
                else {
                    RelativePattern relativePattern = watcher.getGlobPattern().getRight();
                    String baseUri = relativePattern.getBaseUri().map(it -> it.getUri(), it -> it);
                    VirtualFile baseDir = myClient.getDescriptor().findFileByUri(baseUri);
                    if (baseDir != null && baseDir.isDirectory()) {
                        String globPattern = relativePattern.getPattern();
                        if (myClient.getGlobMatcher().pathMatches(fileChangeInfo.getPath(),
                            fileChangeInfo.isDirectory(),
                            globPattern,
                            myClient.getDescriptor().getFilePath(baseDir))) {
                            return new FileEvent(fileChangeInfo.getUri(), fileChangeInfo.getChangeType());
                        }
                    }
                }
            }
        }
        return null;
    }
}

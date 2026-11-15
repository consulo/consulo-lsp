package com.intellij.platform.lsp.impl.logging;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.Application;
import consulo.application.util.registry.Registry;
import consulo.container.boot.ContainerPathManager;
import consulo.logging.Logger;
import consulo.util.io.PathKt;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * This service manages {@link LanguageServiceLogger} instances.
 * {@link LanguageServiceLogger} logs communication between the IDE and external language services (for example, LSP servers).
 * Communication logs are written to separate files in the {@code language-services} folder, which is located in the IDE log directory.
 */
@ServiceAPI(ComponentScope.APPLICATION)
@ServiceImpl
@Singleton
public class LanguageServiceLoggerService {
    private static final Logger LOG = Logger.getInstance(LanguageServiceLoggerService.class);

    private static final DateTimeFormatter LOG_FILENAME_SUFFIX_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    public static LanguageServiceLoggerService getInstance() {
        return Application.get().getInstance(LanguageServiceLoggerService.class);
    }

    public static boolean isDebugLogEnabled() {
        return LOG.isDebugEnabled();
    }

    private final ConcurrentHashMap<String, LoggerBucket> myLoggers = new ConcurrentHashMap<>();

    @Inject
    public LanguageServiceLoggerService() {
    }

    public @Nullable LanguageServiceLogger connect(String logFileName) {
        return connect(logFileName, false);
    }

    public @Nullable LanguageServiceLogger connect(String logFileName, boolean writeStandardLogIfLimitReached) {
        String sanitizedFileName = PathKt.sanitizeFileName(logFileName);
        LoggerBucket bucket = myLoggers.computeIfAbsent(sanitizedFileName, LoggerBucket::new);
        return bucket.connect(writeStandardLogIfLimitReached);
    }

    public void disconnect(LanguageServiceLogger logger) {
        LoggerBucket bucket = null;
        for (LoggerBucket b : myLoggers.values()) {
            if (b.containsLogger(logger)) {
                bucket = b;
                break;
            }
        }
        if (bucket == null) {
            LOG.error("Cannot find a bucket of " + logger.getLogPath().getFileName());
            return;
        }
        bucket.disconnect(logger);
    }

    public List<Path> getActiveLogPaths() {
        List<Path> result = new ArrayList<>();
        for (LoggerBucket bucket : myLoggers.values()) {
            result.addAll(bucket.getActiveLogPaths());
        }
        return result;
    }

    private static int getMaxLogFileCount() {
        String property = System.getProperty("lsp.server.logging.maxLogFiles");
        if (property != null) {
            try {
                return Integer.parseInt(property);
            }
            catch (NumberFormatException ignored) {
            }
        }
        return Registry.intValue("lsp.server.logging.maxLogFiles", 5);
    }

    private static Path getLogPathDir() {
        return ContainerPathManager.get().getLogPath().toPath().resolve("language-services");
    }

    private static final class LoggerBucket {
        private final String myFileName;
        private final Object myLock = new Object();
        private final List<LanguageServiceLogger> myActiveLoggers = new ArrayList<>();

        LoggerBucket(String fileName) {
            myFileName = fileName;
        }

        @Nullable LanguageServiceLogger connect(boolean writeStandardLogIfLimitReached) {
            synchronized (myLock) {
                if (myActiveLoggers.size() >= getMaxLogFileCount()) {
                    LOG.warn("Cannot create a logger for " + myFileName + ", too many active loggers");
                    return null;
                }
                Path logPathDir = getLogPathDir();
                long currentTimeMillis = System.currentTimeMillis();
                String newFileName;
                while (true) {
                    newFileName = myFileName + "-" + millisToPrintableTime(currentTimeMillis) + ".log";
                    String candidate = newFileName;
                    if (myActiveLoggers.stream().anyMatch(it -> it.getLogPath().getFileName().toString().equals(candidate)) ||
                        Files.exists(logPathDir.resolve(newFileName))) {
                        currentTimeMillis++;
                    }
                    else {
                        break;
                    }
                }

                try {
                    Files.createDirectories(logPathDir);
                }
                catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                Path logPath = logPathDir.resolve(newFileName);
                LanguageServiceLogger logger = new LanguageServiceLogger(logPath, writeStandardLogIfLimitReached);

                myActiveLoggers.add(logger);

                // uri is clickable in the debugger console but not when opening logs in the editor
                LOG.info("Created service logger at: " + logPath.toUri());

                Application.get().executeOnPooledThread(this::cleanup);

                return logger;
            }
        }

        private void cleanup() {
            Pattern fileNamePattern = Pattern.compile(Pattern.quote(myFileName) + "-\\d{8}-\\d{6}-\\d{3}\\.log");
            synchronized (myLock) {
                List<Path> files;
                try (Stream<Path> stream = Files.list(getLogPathDir())) {
                    files = stream
                        .filter(it -> {
                            String fileName = it.getFileName().toString();
                            return fileNamePattern.matcher(fileName).matches() &&
                                myActiveLoggers.stream().noneMatch(activeLogger -> activeLogger.getLogPath().getFileName().toString().equals(fileName));
                        })
                        .sorted()
                        .toList();
                }
                catch (IOException e) {
                    LOG.warn("Failed to list " + getLogPathDir(), e);
                    return;
                }
                int toKeep = getMaxLogFileCount() - myActiveLoggers.size();
                if (toKeep >= files.size()) {
                    return;
                }
                // support the case when MAX_LOG_FILE_COUNT can change, so toKeep can be negative
                int toRemove = Math.min(files.size(), files.size() - toKeep);
                for (Path file : files.subList(0, toRemove)) {
                    try {
                        Files.deleteIfExists(file);
                    }
                    catch (IOException e) {
                        LOG.warn("Failed to delete " + file, e);
                    }
                }
            }
        }

        private static String millisToPrintableTime(long millis) {
            return LOG_FILENAME_SUFFIX_FORMAT.format(millisToDate(millis));
        }

        boolean containsLogger(LanguageServiceLogger logger) {
            synchronized (myLock) {
                return myActiveLoggers.contains(logger);
            }
        }

        void disconnect(LanguageServiceLogger logger) {
            logger.close();
            synchronized (myLock) {
                if (!myActiveLoggers.remove(logger)) {
                    LOG.error("Logger " + logger.getLogPath().getFileName() + " was not registered.");
                }
            }
        }

        List<Path> getActiveLogPaths() {
            synchronized (myLock) {
                return myActiveLoggers.stream().map(LanguageServiceLogger::getLogPath).toList();
            }
        }
    }

    public static LocalDateTime millisToDate(long millis) {
        // similar to com.intellij.openapi.diagnostic.IdeaLogRecordFormatter#format
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }
}

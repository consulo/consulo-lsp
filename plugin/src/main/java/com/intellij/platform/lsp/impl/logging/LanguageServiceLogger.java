package com.intellij.platform.lsp.impl.logging;

import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.application.util.registry.Registry;
import consulo.logging.Logger;
import consulo.util.lang.StringUtil;
import org.jspecify.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * @see LanguageServiceLoggerService
 */
public class LanguageServiceLogger {
    private static final int LOG_SIZE_LIMIT_CHARS = 100_000_000;
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Logger LOG = Logger.getInstance(LanguageServiceLoggerService.class);

    /**
     * A single flush is scheduled at most once per this period.
     */
    private static final long FLUSH_PERIOD_MS = 1000;

    private final Path myLogPath;
    private final boolean myWriteStandardLogIfLimitReached;

    private int myLogSizeChars = 0;
    private @Nullable PrintWriter myWriter;
    private @Nullable ScheduledFuture<?> myScheduledFlush;
    private boolean myClosed;

    LanguageServiceLogger(Path logPath, boolean writeStandardLogIfLimitReached) {
        myLogPath = logPath;
        myWriteStandardLogIfLimitReached = writeStandardLogIfLimitReached;
    }

    public Path getLogPath() {
        return myLogPath;
    }

    private PrintWriter getWriter() {
        PrintWriter writer = myWriter;
        if (writer == null) {
            try {
                writer = new PrintWriter(new BufferedOutputStream(
                    Files.newOutputStream(myLogPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)));
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            myWriter = writer;
        }
        return writer;
    }

    private boolean isLogSizeLimitReached() {
        return myLogSizeChars >= LOG_SIZE_LIMIT_CHARS;
    }

    public void logInbound(CharSequence message) {
        doLog("IN ", message, this::logInboundToIdeaLog);
    }

    public void logOutbound(CharSequence message) {
        doLog("OUT", message, this::logOutboundToIdeaLog);
    }

    public void logError(CharSequence message) {
        doLog("ERR", message, this::logErrorToIdeaLog);
    }

    private void logInboundToIdeaLog(CharSequence message) {
        LOG.debug("<-- " + myLogPath.getFileName() + ": " + shorten(message));
    }

    private void logOutboundToIdeaLog(CharSequence message) {
        LOG.debug("--> " + myLogPath.getFileName() + ": " + shorten(message));
    }

    private void logErrorToIdeaLog(CharSequence message) {
        LOG.debug("<xx " + myLogPath.getFileName() + ": " + shorten(message));
    }

    private static String shorten(CharSequence message) {
        return StringUtil.shortenTextWithEllipsis(message.toString(), 3000, 500);
    }

    private synchronized void doLog(String channelTag, CharSequence message, Consumer<CharSequence> logToIdeaLog) {
        if (isLogSizeLimitReached() && myWriteStandardLogIfLimitReached ||
            Registry.is("lsp.communication.standard.log.file", false)) {
            logToIdeaLog.accept(message);
        }

        if (isLogSizeLimitReached() || myClosed) {
            return;
        }

        myLogSizeChars += message.length();

        PrintWriter writer = getWriter();
        for (String line : message.toString().split("\r\n|\r|\n", -1)) {
            if (!line.isEmpty()) {
                writer.write(formatCurrentTime() + ' ' + channelTag + ' ' + line + "\n");
            }
        }

        if (isLogSizeLimitReached()) {
            writer.write("LOG SIZE LIMIT REACHED\n");
            close();
            return;
        }

        requestFlush();
    }

    private void requestFlush() {
        if (myScheduledFlush == null || myScheduledFlush.isDone()) {
            myScheduledFlush = AppExecutorUtil.getAppScheduledExecutorService().schedule(this::flush, FLUSH_PERIOD_MS, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void flush() {
        PrintWriter writer = myWriter;
        if (writer != null && !myClosed) {
            writer.flush();
        }
    }

    private static String formatCurrentTime() {
        return TIMESTAMP_FORMAT.format(LanguageServiceLoggerService.millisToDate(System.currentTimeMillis()));
    }

    synchronized void close() {
        myClosed = true;
        ScheduledFuture<?> scheduledFlush = myScheduledFlush;
        if (scheduledFlush != null) {
            scheduledFlush.cancel(false);
        }
        myScheduledFlush = null;
        PrintWriter writer = myWriter;
        if (writer != null) {
            writer.close();
        }
    }
}

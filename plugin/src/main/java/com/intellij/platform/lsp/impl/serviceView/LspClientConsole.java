package com.intellij.platform.lsp.impl.serviceView;

import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.execution.ui.console.ConsoleView;
import consulo.execution.ui.console.ConsoleViewContentType;
import consulo.execution.ui.console.TextConsoleBuilder;
import consulo.execution.ui.console.TextConsoleBuilderFactory;
import consulo.process.ProcessOutputType;
import consulo.process.ProcessOutputTypes;
import consulo.process.util.AnsiEscapeDecoder;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.ActionManager;
import consulo.ui.ex.action.ActionToolbar;
import consulo.ui.ex.action.DefaultActionGroup;
import consulo.util.dataholder.Key;
import consulo.util.lang.StringUtil;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage;
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;
import org.jspecify.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * A console shown in the Services tool window for a single {@link com.intellij.platform.lsp.api.LspClient}.
 * Messages may arrive from any thread before the UI component is created; {@link ConsoleView} buffers them.
 */
public class LspClientConsole implements Disposable {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_TRAFFIC_PAYLOAD_LENGTH = 10_000;
    private static final int MAX_STORED_PAYLOAD_LENGTH = 100_000;
    private static final int MAX_INCOMPLETE_LINE_LENGTH = 10_000;

    private final ConsoleView myConsole;
    private final AnsiEscapeDecoder myAnsiDecoder = new AnsiEscapeDecoder();
    private final ProcessOutputLines myStandardOutputLines = new ProcessOutputLines("STDOUT", ConsoleViewContentType.NORMAL_OUTPUT);
    private final ProcessOutputLines myErrorOutputLines = new ProcessOutputLines("STDERR", ConsoleViewContentType.ERROR_OUTPUT);
    private @Nullable JComponent myPanel;

    public LspClientConsole(Project project) {
        TextConsoleBuilder builder = TextConsoleBuilderFactory.getInstance().createBuilder(project);
        builder.setViewer(true);
        myConsole = builder.getConsole();
        Disposer.register(this, myConsole);
    }

    public void printLogMessage(MessageType type, String message) {
        print(levelTag(type), message, contentType(type));
    }

    public void printShowMessage(MessageType type, String message) {
        print(levelTag(type), "window/showMessage: " + message, contentType(type));
    }

    /**
     * Prints a raw chunk of the text that the server process wrote to its standard output or to its standard error stream.
     * Many servers write their log there. A chunk can end in the middle of a line.
     * The console keeps the incomplete last line until the next chunk completes it, or until {@link #flushProcessOutput}.
     */
    public synchronized void printProcessOutput(String chunk, Key<?> outputType) {
        if (ProcessOutputType.isStderr(outputType)) {
            myErrorOutputLines.append(chunk);
        }
        else if (ProcessOutputType.isStdout(outputType)) {
            myStandardOutputLines.append(chunk);
        }
    }

    /**
     * Prints the incomplete last lines of the server process output. Call it when the server process terminates.
     */
    public synchronized void flushProcessOutput() {
        myStandardOutputLines.flush();
        myErrorOutputLines.flush();
    }

    public void printTrace(String message) {
        print("TRACE", message, ConsoleViewContentType.LOG_VERBOSE_OUTPUT);
    }

    public void printLifecycle(String message) {
        printLifecycle(message, false);
    }

    public void printLifecycle(String message, boolean error) {
        print(null, message, error ? ConsoleViewContentType.LOG_ERROR_OUTPUT : ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    public synchronized void printTraffic(boolean outbound, Message message, String json) {
        String header;
        if (message instanceof RequestMessage requestMessage) {
            header = "request '" + requestMessage.getMethod() + "' (id=" + requestMessage.getId() + ")";
        }
        else if (message instanceof ResponseMessage responseMessage) {
            header = "response (id=" + responseMessage.getId() + ")";
        }
        else if (message instanceof NotificationMessage notificationMessage) {
            header = "notification '" + notificationMessage.getMethod() + "'";
        }
        else {
            header = "message";
        }
        String payloadPreview = trafficPayloadPreview(json);
        ConsoleViewContentType contentType = outbound ? ConsoleViewContentType.LOG_DEBUG_OUTPUT : ConsoleViewContentType.LOG_VERBOSE_OUTPUT;
        String tag = outbound ? "OUT" : "IN";
        String arrow = outbound ? "→" : "←";

        myConsole.print(LocalTime.now().format(TIMESTAMP_FORMAT) + " " + padEnd(tag) + " ", contentType);
        myConsole.printHyperlink(header, new LspTrafficPayloadHyperlinkInfo(
            arrow + " " + header,
            json.length() > MAX_STORED_PAYLOAD_LENGTH ? json.substring(0, MAX_STORED_PAYLOAD_LENGTH) : json,
            json.length() > MAX_STORED_PAYLOAD_LENGTH
        ));
        myConsole.print(": " + payloadPreview + "\n", contentType);
    }

    private void print(@Nullable String tag, String message, ConsoleViewContentType contentType) {
        print(tag, message, contentType, true);
    }

    private synchronized void print(@Nullable String tag, String message, ConsoleViewContentType contentType, boolean decodeAnsi) {
        CharSequence cleanedMessage;
        if (decodeAnsi) {
            StringBuilder builder = new StringBuilder();
            myAnsiDecoder.escapeText(message, ProcessOutputTypes.STDOUT, (text, attributes) -> builder.append(text));
            cleanedMessage = builder;
        }
        else {
            cleanedMessage = message;
        }

        StringBuilder line = new StringBuilder();
        line.append(LocalTime.now().format(TIMESTAMP_FORMAT)).append(' ');
        if (tag != null) {
            line.append(padEnd(tag)).append(' ');
        }
        line.append(cleanedMessage.toString().stripTrailing());
        line.append('\n');
        myConsole.print(line.toString(), contentType);
    }

    @RequiredUIAccess
    public JComponent getComponent() {
        JComponent panel = myPanel;
        if (panel != null) {
            return panel;
        }

        JPanel newPanel = new JPanel(new BorderLayout());
        newPanel.add(myConsole.getComponent(), BorderLayout.CENTER);

        ActionToolbar toolbar = ActionManager.getInstance()
            .createActionToolbar("LspServiceViewConsole", new DefaultActionGroup(myConsole.createConsoleActions()), false);
        toolbar.setTargetComponent(myConsole.getComponent());
        newPanel.add(toolbar.getComponent(), BorderLayout.EAST);

        myPanel = newPanel;
        return newPanel;
    }

    @Override
    public void dispose() {
        myPanel = null;
    }

    private static String padEnd(String tag) {
        return String.format("%-5s", tag);
    }

    /**
     * Puts the chunks of one output stream of the server process together into lines.
     * {@code \n}, {@code \r\n}, and a lone {@code \r} end a line. A line longer than {@link #MAX_INCOMPLETE_LINE_LENGTH} is printed in parts.
     * Use it only under the lock of the console.
     */
    private class ProcessOutputLines {
        private final String myTag;
        private final ConsoleViewContentType myContentType;
        private final StringBuilder myIncompleteLine = new StringBuilder();

        ProcessOutputLines(String tag, ConsoleViewContentType contentType) {
            myTag = tag;
            myContentType = contentType;
        }

        void append(String chunk) {
            myIncompleteLine.append(chunk);
            // A '\r' at the end can be the first half of "\r\n". It waits for the next chunk.
            int length = myIncompleteLine.length();
            int searchEnd = length > 0 && myIncompleteLine.charAt(length - 1) == '\r' ? length - 2 : length - 1;
            int lastLineBreak = -1;
            for (int i = searchEnd; i >= 0; i--) {
                char c = myIncompleteLine.charAt(i);
                if (c == '\n' || c == '\r') {
                    lastLineBreak = i;
                    break;
                }
            }
            if (lastLineBreak >= 0) {
                printLines(myIncompleteLine.substring(0, lastLineBreak));
                myIncompleteLine.delete(0, lastLineBreak + 1);
            }
            if (myIncompleteLine.length() > MAX_INCOMPLETE_LINE_LENGTH) {
                flush();
            }
        }

        void flush() {
            printLines(myIncompleteLine.toString());
            myIncompleteLine.setLength(0);
        }

        private void printLines(String text) {
            // "\r\n", "\n" and "\r" end a line
            for (String line : text.split("\r\n|\n|\r", -1)) {
                if (!line.isBlank()) {
                    print(myTag, line, myContentType);
                }
            }
        }
    }

    /**
     * Keeps the whole payload on one console line. The hyperlink popup shows the formatted payload.
     */
    public static String trafficPayloadPreview(String json) {
        // collapseWhiteSpace does not treat '\r' as white space, so normalize the line separators first
        String singleLine = collapseWhiteSpace(StringUtil.convertLineSeparators(json));
        if (singleLine.length() > MAX_TRAFFIC_PAYLOAD_LENGTH) {
            return singleLine.substring(0, MAX_TRAFFIC_PAYLOAD_LENGTH) +
                "… (" + (singleLine.length() - MAX_TRAFFIC_PAYLOAD_LENGTH) + " more characters)";
        }
        return singleLine;
    }

    /**
     * Replaces each run of spaces, tabs and line feeds with a single space and trims them at both ends.
     */
    private static String collapseWhiteSpace(CharSequence s) {
        StringBuilder result = null;
        int length = s.length();
        for (int i = 0; i < length; ) {
            char ch = s.charAt(i);
            int ni = i;
            while (ni < length && isWhitespaceTabOrNewLine(s.charAt(ni))) {
                ni++;
            }
            if (ni == i + 1 && (i == 0 || ch != ' ' || ni == length) || ni > i + 1) {
                if (result == null) {
                    result = new StringBuilder(length);
                    result.append(s, 0, i);
                }
                if (i != 0 && ni != length) {
                    result.append(' ');
                }
                i = ni;
            }
            else {
                if (result != null) {
                    result.append(ch);
                }
                i++;
            }
        }
        return result == null ? s.toString() : result.toString();
    }

    private static boolean isWhitespaceTabOrNewLine(char c) {
        return c == ' ' || c == '\t' || c == '\n';
    }

    private static String levelTag(MessageType type) {
        return switch (type) {
            case Error -> "ERROR";
            case Warning -> "WARN";
            case Info -> "INFO";
            case Log -> "LOG";
            case Debug -> "DEBUG";
        };
    }

    private static ConsoleViewContentType contentType(MessageType type) {
        return switch (type) {
            case Error -> ConsoleViewContentType.LOG_ERROR_OUTPUT;
            case Warning -> ConsoleViewContentType.LOG_WARNING_OUTPUT;
            case Info -> ConsoleViewContentType.LOG_INFO_OUTPUT;
            case Log, Debug -> ConsoleViewContentType.LOG_DEBUG_OUTPUT;
        };
    }
}

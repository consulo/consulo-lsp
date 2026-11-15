// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.api;

import org.jspecify.annotations.Nullable;

/**
 * The channel for the communication between the IDE and the LSP server.
 *
 * @see LspClientDescriptor#getLspCommunicationChannel()
 */
public abstract sealed class LspCommunicationChannel permits LspCommunicationChannel.StdIO, LspCommunicationChannel.Socket {
    private LspCommunicationChannel() {
    }

    public static final class StdIO extends LspCommunicationChannel {
        public static final StdIO INSTANCE = new StdIO();

        private StdIO() {
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return o instanceof StdIO;
        }

        @Override
        public int hashCode() {
            return StdIO.class.getName().hashCode();
        }

        @Override
        public String toString() {
            return "StdIO";
        }
    }

    /**
     * {@code startProcess}: {@code true} means that the IDE should start the LSP server by calling
     * {@link LspClientDescriptor#startServerProcess()} and then to connect to the started server via a socket connection
     * using the specified {@code port}; {@code false} means that the LSP server is already running,
     * so the IDE should only connect to it via a socket connection
     */
    public static final class Socket extends LspCommunicationChannel {
        private final int myPort;
        private final boolean myStartProcess;

        public Socket(int port) {
            this(port, true);
        }

        public Socket(int port, boolean startProcess) {
            myPort = port;
            myStartProcess = startProcess;
        }

        public int getPort() {
            return myPort;
        }

        public boolean getStartProcess() {
            return myStartProcess;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Socket socket)) {
                return false;
            }
            return myPort == socket.myPort && myStartProcess == socket.myStartProcess;
        }

        @Override
        public int hashCode() {
            return 31 * Integer.hashCode(myPort) + Boolean.hashCode(myStartProcess);
        }

        @Override
        public String toString() {
            return "Socket(port=" + myPort + ", startProcess=" + myStartProcess + ")";
        }
    }
}

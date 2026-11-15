// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
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

import consulo.logging.Logger;
import consulo.process.BaseProcessHandler;
import consulo.process.ExecutionException;
import consulo.process.ProcessOutputTypes;
import consulo.process.TaskExecutor;
import consulo.process.cmd.GeneralCommandLine;
import consulo.process.event.ProcessEvent;
import consulo.process.event.ProcessListener;
import consulo.process.io.BaseDataReader;
import consulo.process.io.BaseInputStreamReader;
import consulo.process.io.BaseOutputReader;
import consulo.process.io.ProcessIOExecutorService;
import consulo.process.util.ProcessWaitFor;
import consulo.util.dataholder.Key;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * A minimal process handler for an OS process.
 * <p>
 * Consulo's own {@code OSProcessHandler} lives in an internal package, and {@code ProcessHandlerBuilder}
 * does not allow overriding {@link #readerOptions()}. The LSP client needs that: the output reader must not split the
 * server output into lines, because it would drop {@code '\r'} of {@code "\r\n"}, while the char count must match the
 * {@code Content-Length} header.
 * <p>
 * The process tree is destroyed with the {@link ProcessHandle} API.
 */
public class OSProcessHandler extends BaseProcessHandler implements TaskExecutor {
    private static final Logger LOG = Logger.getInstance(OSProcessHandler.class);

    protected final Process myProcess;
    protected final String myCommandLine;
    protected final @Nullable Charset myCharset;
    protected final ProcessWaitFor myWaitFor;
    private final boolean myHasErrorStream;

    public OSProcessHandler(GeneralCommandLine commandLine) throws ExecutionException {
        this(commandLine.createProcess(), commandLine.getCommandLineString(), commandLine.getCharset(), !commandLine.isRedirectErrorStream());
    }

    /**
     * {@code commandLine} must not be empty (for correct thread attribution in the stacktrace)
     */
    public OSProcessHandler(Process process, String commandLine, @Nullable Charset charset) {
        this(process, commandLine, charset, true);
    }

    private OSProcessHandler(Process process, String commandLine, @Nullable Charset charset, boolean hasErrorStream) {
        myProcess = process;
        myCommandLine = commandLine;
        myCharset = charset;
        myHasErrorStream = hasErrorStream;
        myWaitFor = new ProcessWaitFor(process, this, commandLine);
    }

    public Process getProcess() {
        return myProcess;
    }

    @Override
    public String getCommandLine() {
        return myCommandLine;
    }

    @Override
    public @Nullable Charset getCharset() {
        return myCharset;
    }

    @Override
    public long getId() {
        return myProcess.pid();
    }

    @Override
    public OutputStream getProcessInput() {
        return myProcess.getOutputStream();
    }

    @Override
    public Future<?> executeTask(Runnable task) {
        return ProcessIOExecutorService.INSTANCE.submit(task);
    }

    /**
     * Override this method to fine-tune {@link BaseOutputReader} behavior.
     */
    protected BaseOutputReader.Options readerOptions() {
        if (Boolean.getBoolean("output.reader.blocking.mode")) {
            return BaseOutputReader.Options.BLOCKING;
        }
        else {
            return BaseOutputReader.Options.NON_BLOCKING;
        }
    }

    @Override
    public void startNotify() {
        notifyTextAvailable(myCommandLine + '\n', ProcessOutputTypes.SYSTEM);

        addProcessListener(new ProcessListener() {
            @Override
            public void startNotified(ProcessEvent event) {
                try {
                    BaseDataReader stdOutReader = new SimpleOutputReader(createInputStreamReader(myProcess.getInputStream()),
                        ProcessOutputTypes.STDOUT, readerOptions(), "output stream of " + myCommandLine);
                    BaseDataReader stdErrReader = myHasErrorStream
                        ? new SimpleOutputReader(createInputStreamReader(myProcess.getErrorStream()),
                        ProcessOutputTypes.STDERR, readerOptions(), "error stream of " + myCommandLine)
                        : null;

                    myWaitFor.setTerminationCallback(exitCode -> {
                        try {
                            // tell readers that no more attempts to read process' output should be made
                            if (stdErrReader != null) {
                                stdErrReader.stop();
                            }
                            stdOutReader.stop();

                            try {
                                if (stdErrReader != null) {
                                    stdErrReader.waitFor();
                                }
                                stdOutReader.waitFor();
                            }
                            catch (InterruptedException ignore) {
                            }
                        }
                        finally {
                            notifyProcessTerminated(exitCode);
                        }
                    });
                }
                finally {
                    removeProcessListener(this);
                }
            }
        });

        super.startNotify();
    }

    private Reader createInputStreamReader(InputStream streamToRead) {
        Charset charset = getCharset();
        if (charset == null) {
            charset = Charset.defaultCharset();
        }
        return new BaseInputStreamReader(streamToRead, charset);
    }

    @Override
    protected void destroyProcessImpl() {
        try {
            closeStreams();
        }
        finally {
            doDestroyProcess();
        }
    }

    /**
     * Destroys the whole process tree, the default Java behavior doesn't kill children processes.
     */
    protected void doDestroyProcess() {
        executeTask(() -> {
            myProcess.descendants().forEach(ProcessHandle::destroy);
            myProcess.destroy();
        });
    }

    @Override
    protected void detachProcessImpl() {
        executeTask(() -> {
            closeStreams();

            myWaitFor.detach();
            notifyProcessDetached();
        });
    }

    @Override
    public boolean detachIsDefault() {
        return false;
    }

    protected void closeStreams() {
        try {
            myProcess.getOutputStream().close();
        }
        catch (IOException e) {
            LOG.warn(e);
        }
    }

    @Override
    public boolean waitFor() {
        boolean result = super.waitFor();
        try {
            myWaitFor.waitFor();
        }
        catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    @Override
    public boolean waitFor(long timeoutInMilliseconds) {
        long start = System.currentTimeMillis();
        boolean result = super.waitFor(timeoutInMilliseconds);
        long elapsed = System.currentTimeMillis() - start;
        try {
            result &= myWaitFor.waitFor(Math.max(0, timeoutInMilliseconds - elapsed), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    @Override
    public String toString() {
        return myCommandLine;
    }

    protected class SimpleOutputReader extends BaseOutputReader {
        private final Key<?> myProcessOutputType;

        public SimpleOutputReader(Reader reader, Key<?> outputType, Options options, String presentableName) {
            super(reader, options);
            myProcessOutputType = outputType;
            start(presentableName);
        }

        @Override
        protected Future<?> executeOnPooledThread(Runnable runnable) {
            return OSProcessHandler.this.executeTask(runnable);
        }

        @Override
        protected void onTextAvailable(String text) {
            notifyTextAvailable(text, myProcessOutputType);
        }
    }
}

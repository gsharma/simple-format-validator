package org.validator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PipedReader;
import java.util.ArrayList;
import java.util.EmptyStackException;
import java.util.Iterator;
import java.util.List;
import java.util.Stack;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The goal of this tool is to check streaming text to see if it complies with a
 * given programming language syntax policy. In the simplest case, a compliance
 * example of streaming text could be checking open/close pairs of say
 * paranthesis in a source code file or a fragment of the file. Policies are
 * pluggable and do not necessarily have to enforce element pairs.
 */
public class SyntaxChecker {
    private StreamingMode mode;
    private ValidationPolicy[] policies;
    private String source;
    private PipedReader sourceReader;
    private StreamingValidator streamer;
    private volatile State state = State.INITIALIZING;

    // dataSource can either be a string or a file
    public SyntaxChecker(final StreamingMode streamingMode,
            final ValidationPolicy[] policies, final String dataSource) {
        if (dataSource == null || dataSource.length() == 0) {
            throw new IllegalArgumentException(
                    "StreamingSource cannot be null or empty");
        }
        this.source = dataSource;
        if (streamingMode == StreamingMode.PIPE) {
            throw new IllegalArgumentException(
                    "PIPE StreamingMode is not supported with file or string streamingSources");
        }

        init(streamingMode, policies);
    }

    public SyntaxChecker(final StreamingMode streamingMode,
            final ValidationPolicy[] policies, final PipedReader reader) {
        if (reader == null) {
            throw new IllegalArgumentException(
                    "Piped reader cannot be null or empty");
        }
        this.sourceReader = reader;
        if (streamingMode != StreamingMode.PIPE) {
            throw new IllegalArgumentException(
                    "PIPE StreamingMode is required with pipedreader streamingSource");
        }

        init(streamingMode, policies);
    }

    private SyntaxChecker() {
    }

    public Result validateNow() {
        return streamer.validate();
    }

    public void stopNow() {
        streamer.stop();
    }

    public State getState() {
        return state;
    }

    private void init(final StreamingMode mode,
            final ValidationPolicy[] policies) {
        init(mode);
        init(policies);
        streamer = new StreamingValidator();
        Thread streamerThread = new Thread(streamer);
        streamerThread.start();
    }

    private void init(final StreamingMode streamingMode) {
        if (streamingMode == null) {
            throw new IllegalArgumentException("StreamingMode cannot be null");
        }
        this.mode = streamingMode;
    }

    private void init(final ValidationPolicy[] policies) {
        if (policies == null || policies.length == 0) {
            throw new IllegalArgumentException(
                    "Validation policies cannot be null or empty");
        }
        this.policies = policies;
    }

    // Data streamer
    final class StreamingValidator implements Runnable {
        // Fixed-size copy-buffer
        private final Vector<Character> memoryBuffer = new Vector<Character>();
        private volatile boolean stopRequested;

        void stop() {
            if (state == State.STOPPED) {
                throw new UnsupportedOperationException(
                        "Attempting to stop an already stopped instance.");
            }
            stopRequested = true;
            state = State.STOPPED;
        }

        Result validate() {
            if (state == State.PROCESSING) {
                throw new UnsupportedOperationException(
                        "This instance is processing a validation request. Please wait for completion or fork another instance");
            }

            state = State.PROCESSING;
            Result result = new Result();
            for (ValidationPolicy policy : policies) {
                PluggablePolicy delegate = policy.getDelegate(this);
                result.addResult(delegate.validate());
            }

            memoryBuffer.clear(); // purge buffer

            return result;
        }

        @Override
        public void run() {
            while (!stopRequested) {
                // be ready to handle requests
            }
        }

        Vector<Character> getMemoryBuffer() {
            return memoryBuffer;
        }

        StreamingMode getStreamingMode() {
            return mode;
        }

        synchronized void stream() {
            switch (mode) {
            case STRING:
                streamer.streamFromString();
                break;
            case FILE:
                streamer.streamFromFile();
                break;
            case PIPE:
                streamer.streamFromPipe();
                break;
            }
        }

        private synchronized void streamFromFile() {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(new File(source)));
                int charRead;
                while ((charRead = reader.read()) != -1) {
                    memoryBuffer.add((char) charRead);
                }

                /*
                 * // mmap the file buffer.clear(); FileChannel fileChannel =
                 * null; fileChannel = new FileInputStream(new File(source))
                 * .getChannel(); CharBuffer charBuffer = fileChannel.map(
                 * FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
                 * .asCharBuffer(); while (charBuffer.hasRemaining()) {
                 * buffer.add(charBuffer.get()); } fileChannel.close();
                 */
            } catch (IOException ioException) {
                throw new RuntimeException(ioException);
            } finally {
                try {
                    if (reader != null) {
                        reader.close();
                    }
                } catch (IOException ioException) {
                    throw new RuntimeException(ioException);
                }
            }
        }

        private synchronized void streamFromString() {
            char[] characterStream = source.toCharArray();
            if (characterStream != null && characterStream.length > 0) {
                for (char stringChar : characterStream) {
                    memoryBuffer.add(stringChar);
                }
            }
        }

        private void streamFromPipe() {
            try {
                int charRead;
                while ((charRead = sourceReader.read()) != -1) {
                    memoryBuffer.add((char) charRead);
                }
            } catch (IOException ioException) {
                throw new RuntimeException(ioException);
            }
        }
    }

    public static enum StreamingMode {
        FILE, STRING, PIPE;
    }

    public enum ValidationPolicy {
        BRACE(BracePolicy.class);

        private final Class<? extends PluggablePolicy> policyDelegateClass;

        private ValidationPolicy(
                final Class<? extends PluggablePolicy> policyDelegateClass) {
            this.policyDelegateClass = policyDelegateClass;
        }

        PluggablePolicy getDelegate(final StreamingValidator streamer) {
            PluggablePolicy delegate = null;
            // factory hack
            if (policyDelegateClass.equals(BracePolicy.class)) {
                delegate = new SyntaxChecker().new BracePolicy();
            }

            if (delegate != null) {
                delegate.inject(streamer);
            }

            return delegate;
        }
    }

    abstract class PluggablePolicy {
        protected AtomicInteger validationsPerformed = new AtomicInteger();
        protected AtomicInteger lastTotalCharsValidated = new AtomicInteger();

        abstract PolicyResult validate();

        abstract void inject(final StreamingValidator streamer);
    }

    final class BracePolicy extends PluggablePolicy {
        private final Stack<Character> stack = new Stack<Character>();
        private StreamingValidator streamer;

        @Override
        void inject(final StreamingValidator streamer) {
            this.streamer = streamer;
        }

        @Override
        synchronized PolicyResult validate() {
            streamer.stream(); // todo: better get a read-lock on streamer

            ResultType resultType = ResultType.VALID;
            if (!streamer.getMemoryBuffer().isEmpty()) {
                validationsPerformed.incrementAndGet();
                for (Iterator<Character> iter = streamer.getMemoryBuffer()
                        .iterator(); iter.hasNext();) {
                    char c = iter.next();
                    lastTotalCharsValidated.incrementAndGet();
                    try {
                        switch (c) {
                        case Braces.START_PAREN:
                        case Braces.START_BRACE:
                        case Braces.START_SQBRACE:
                            stack.add(c);
                            break;
                        case Braces.END_PAREN:
                            if (stack.pop() != Braces.START_PAREN) {
                                return constructResultWrapper(ResultType.INVALID);
                            }
                            break;
                        case Braces.END_BRACE:
                            if (stack.pop() != Braces.START_BRACE) {
                                return constructResultWrapper(ResultType.INVALID);
                            }
                            break;
                        case Braces.END_SQBRACE:
                            if (stack.pop() != Braces.START_SQBRACE) {
                                return constructResultWrapper(ResultType.INVALID);
                            }
                            break;
                        }
                    } catch (EmptyStackException emptyStack) {
                        return constructResultWrapper(ResultType.INVALID);
                    }
                }

                if (!stack.isEmpty()) {
                    resultType = ResultType.INVALID;
                }
            }

            return constructResultWrapper(resultType);
        }

        private synchronized PolicyResult constructResultWrapper(
                final ResultType resultType) {
            return new PolicyResult(resultType, validationsPerformed.get(),
                    lastTotalCharsValidated.get(), streamer.getStreamingMode());
        }

        private final class Braces {
            static final char START_PAREN = '(';
            static final char END_PAREN = ')';
            static final char START_BRACE = '{';
            static final char END_BRACE = '}';
            static final char START_SQBRACE = '[';
            static final char END_SQBRACE = ']';
        }
    }

    public final class Result {
        private List<PolicyResult> policyResults = new ArrayList<PolicyResult>();

        void addResult(final PolicyResult policyResult) {
            policyResults.add(policyResult);
        }

        public PolicyResult[] getDetailedResults() {
            return policyResults
                    .toArray(new PolicyResult[policyResults.size()]);
        }

        @Override
        public String toString() {
            return policyResults.toString();
        }
    }

    // Immutable per-policy result container
    public final class PolicyResult {
        private final int validationsPerformed;
        private final int totalCharactersValidated;
        private final ResultType resultType;
        private final StreamingMode mode;

        public PolicyResult(final ResultType resultType,
                final int validationsPerformed,
                final int totalCharactersValidated, final StreamingMode mode) {
            this.resultType = resultType;
            this.validationsPerformed = validationsPerformed;
            this.totalCharactersValidated = totalCharactersValidated;
            this.mode = mode;
        }

        public int getValidationsPerformed() {
            return validationsPerformed;
        }

        public int getTotalCharactersValidated() {
            return totalCharactersValidated;
        }

        public ResultType getResultType() {
            return resultType;
        }

        public StreamingMode getMode() {
            return mode;
        }

        @Override
        public String toString() {
            return "policyResult=" + resultType + ", totalRuns="
                    + validationsPerformed + ", charsScanned="
                    + totalCharactersValidated + ", mode=" + mode;
        }
    }

    public static enum ResultType {
        VALID, INVALID;
    }

    public static enum State {
        INITIALIZING, PROCESSING, STOPPED; // keep it simple
    }
}

package dev.deschna.scripthub.script.infrastructure.graalvm;

import dev.deschna.scripthub.script.application.ScriptExecutor;
import dev.deschna.scripthub.script.domain.ScriptExecution;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class GraalScriptExecutor implements ScriptExecutor {

    private static final String DEFAULT_LANGUAGE_ID = "js";

    private final Executor executor;
    private final Clock clock;

    public GraalScriptExecutor(
            @Qualifier("scriptExecutionTaskExecutor") Executor executor,
            Clock clock
    ) {
        this.executor = Objects.requireNonNull(executor);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void execute(ScriptExecution execution) {
        Objects.requireNonNull(execution);
        executor.execute(() -> executeScript(execution));
    }

    private void executeScript(ScriptExecution execution) {
        execution.start(clock.instant());
        try (Context context = Context.newBuilder(DEFAULT_LANGUAGE_ID)
                .out(outputStreamFor(execution::appendStandardOutput))
                .err(outputStreamFor(execution::appendErrorOutput))
                .build()) {
            context.eval(DEFAULT_LANGUAGE_ID, execution.getBody());
        } catch (PolyglotException exception) {
            execution.fail(clock.instant(), guestStackTraceOf(exception));
            return;
        }
        execution.complete(clock.instant());
    }

    private OutputStream outputStreamFor(Consumer<String> outputAppender) {
        return new ScriptExecutionOutputStream(outputAppender);
    }

    private String guestStackTraceOf(PolyglotException exception) {
        StringBuilder stackTrace = new StringBuilder(exception.toString());
        for (PolyglotException.StackFrame frame : exception.getPolyglotStackTrace()) {
            if (frame.isGuestFrame()) {
                // Store only script-level frames; Java/GraalVM host frames are internal noise
                // for the script author.
                stackTrace.append(System.lineSeparator())
                        .append("\tat ")
                        .append(frame);
            }
        }
        return stackTrace.toString();
    }

    private static class ScriptExecutionOutputStream extends OutputStream {

        private final Consumer<String> outputAppender;
        // Reused by write(int) to delegate single-byte writes without per-call allocation.
        private final byte[] singleByte = new byte[1];

        ScriptExecutionOutputStream(Consumer<String> outputAppender) {
            this.outputAppender = Objects.requireNonNull(outputAppender);
        }

        @Override
        public void write(int value) {
            singleByte[0] = (byte) value;
            write(singleByte, 0, 1);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            // Preserve OutputStream's offset/length contract before decoding the chunk.
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return;
            }
            outputAppender.accept(new String(buffer, offset, length, StandardCharsets.UTF_8));
        }
    }
}

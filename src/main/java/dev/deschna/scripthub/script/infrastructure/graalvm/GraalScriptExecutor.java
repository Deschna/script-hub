package dev.deschna.scripthub.script.infrastructure.graalvm;

import dev.deschna.scripthub.script.application.ScriptExecutor;
import dev.deschna.scripthub.script.domain.InvalidScriptExecutionStateException;
import dev.deschna.scripthub.script.domain.InvalidScriptExecutionTransitionException;
import dev.deschna.scripthub.script.domain.ScriptExecution;
import dev.deschna.scripthub.script.domain.ScriptStatus;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

@Component
class GraalScriptExecutor implements ScriptExecutor {

    private static final String DEFAULT_LANGUAGE_ID = "js";
    private static final boolean CANCEL_IF_EXECUTING = true;

    private final Executor executor;
    private final TaskScheduler timeoutScheduler;
    private final Clock clock;
    private final GraalScriptContextRegistry contextRegistry;
    private final Duration executionTimeout;

    public GraalScriptExecutor(
            @Qualifier("scriptExecutionTaskExecutor") Executor executor,
            @Qualifier("scriptTimeoutTaskScheduler") TaskScheduler timeoutScheduler,
            Clock clock,
            GraalScriptContextRegistry contextRegistry,
            @Value("${script-hub.execution.timeout}") Duration executionTimeout
    ) {
        this.executor = Objects.requireNonNull(executor);
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler);
        this.clock = Objects.requireNonNull(clock);
        this.contextRegistry = Objects.requireNonNull(contextRegistry);
        this.executionTimeout = requirePositive(executionTimeout);
    }

    @Override
    public void execute(ScriptExecution execution) {
        Objects.requireNonNull(execution);
        executor.execute(() -> executeScript(execution));
    }

    @Override
    public void stop(ScriptExecution execution) {
        Objects.requireNonNull(execution);
        contextRegistry.findByExecutionId(execution.getId())
                .ifPresent(this::stopContext);
    }

    private void executeScript(ScriptExecution execution) {
        // The task may have been stopped while it was still waiting in the executor queue.
        if (!tryStart(execution)) {
            return;
        }
        // Limit the entire RUNNING period, including GraalVM Context initialization.
        ScheduledFuture<?> timeoutTask = scheduleTimeout(execution);
        try (Context context = Context.newBuilder(DEFAULT_LANGUAGE_ID)
                .out(outputStreamFor(execution::appendStandardOutput))
                .err(outputStreamFor(execution::appendErrorOutput))
                .build()) {
            contextRegistry.register(execution.getId(), context);
            // Stop may happen while the Context is being created, before it is available
            // in the registry.
            if (isCancelled(execution)) {
                return;
            }
            context.eval(DEFAULT_LANGUAGE_ID, execution.getBody());
        } catch (PolyglotException exception) {
            // Context.close(true) reports cancellation as PolyglotException. Manually
            // stopped and timed-out executions must retain their final domain status.
            if (isCancelled(execution)) {
                return;
            }
            execution.fail(clock.instant(), guestStackTraceOf(exception));
            return;
        } catch (InvalidScriptExecutionStateException
                | InvalidScriptExecutionTransitionException exception) {
            ignoreIfCancelled(execution, exception);
        } finally {
            timeoutTask.cancel(false);
            contextRegistry.unregister(execution.getId());
        }
        complete(execution);
    }

    private boolean tryStart(ScriptExecution execution) {
        try {
            execution.start(clock.instant());
            return true;
        } catch (InvalidScriptExecutionTransitionException exception) {
            if (isCancelled(execution)) {
                return false;
            }
            throw exception;
        }
    }

    private void complete(ScriptExecution execution) {
        try {
            execution.complete(clock.instant());
        } catch (InvalidScriptExecutionTransitionException exception) {
            ignoreIfCancelled(execution, exception);
        }
    }

    private ScheduledFuture<?> scheduleTimeout(ScriptExecution execution) {
        return timeoutScheduler.schedule(
                () -> timeOut(execution),
                timeoutScheduler.getClock().instant().plus(executionTimeout)
        );
    }

    private void timeOut(ScriptExecution execution) {
        try {
            execution.timeOut(clock.instant());
        } catch (InvalidScriptExecutionTransitionException exception) {
            // Ignore a timeout that lost the terminal-state race; only a successful
            // timeout may cancel the active Context.
            return;
        }
        contextRegistry.findByExecutionId(execution.getId())
                .ifPresent(this::stopContext);
    }

    private boolean isCancelled(ScriptExecution execution) {
        return execution.getStatus() == ScriptStatus.STOPPED
                || execution.getStatus() == ScriptStatus.TIMED_OUT;
    }

    private void stopContext(Context context) {
        context.close(CANCEL_IF_EXECUTING);
    }

    private void ignoreIfCancelled(ScriptExecution execution, RuntimeException exception) {
        // Cancellation changes the domain status before requesting GraalVM to close the Context.
        // Until the close takes effect, output append can fail with a state error, and
        // completion can fail with a transition error. Both are expected only for canceled
        // executions.
        if (!isCancelled(execution)) {
            throw exception;
        }
    }

    private Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Script execution timeout must be positive");
        }
        return timeout;
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

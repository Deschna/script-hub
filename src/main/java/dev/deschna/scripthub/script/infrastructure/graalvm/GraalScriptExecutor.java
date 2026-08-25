package dev.deschna.scripthub.script.infrastructure.graalvm;

import dev.deschna.scripthub.script.application.ScriptExecutionRejectedException;
import dev.deschna.scripthub.script.application.ScriptExecutor;
import dev.deschna.scripthub.script.domain.InvalidScriptExecutionStateException;
import dev.deschna.scripthub.script.domain.InvalidScriptExecutionTransitionException;
import dev.deschna.scripthub.script.domain.ScriptExecution;
import dev.deschna.scripthub.script.domain.ScriptStatus;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

@Component
@Slf4j
class GraalScriptExecutor implements ScriptExecutor {

    private static final boolean CANCEL_IF_EXECUTING = true;
    private static final String RESOURCE_LIMIT_EXCEEDED_MESSAGE =
            "Script execution resource limit exceeded";
    private static final String INTERNAL_ERROR_MESSAGE =
            "Script execution failed due to an internal error";
    private static final String TRUNCATION_MARKER = "…";

    private final Executor executor;
    private final TaskScheduler timeoutScheduler;
    private final Clock clock;
    private final GraalScriptContextRegistry contextRegistry;
    private final GraalScriptContextFactory contextFactory;
    private final Duration executionTimeout;
    private final int maxDiagnosticLength;

    public GraalScriptExecutor(
            @Qualifier("scriptExecutionTaskExecutor") Executor executor,
            @Qualifier("scriptTimeoutTaskScheduler") TaskScheduler timeoutScheduler,
            Clock clock,
            GraalScriptContextRegistry contextRegistry,
            GraalScriptContextFactory contextFactory,
            @Value("${script-hub.execution.timeout}") Duration executionTimeout,
            @Value("${script-hub.execution.diagnostics.max-length}") int maxDiagnosticLength
    ) {
        this.executor = Objects.requireNonNull(executor);
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler);
        this.clock = Objects.requireNonNull(clock);
        this.contextRegistry = Objects.requireNonNull(contextRegistry);
        this.contextFactory = Objects.requireNonNull(contextFactory);
        this.executionTimeout = requirePositiveExecutionTimeout(executionTimeout);
        this.maxDiagnosticLength = requirePositiveDiagnosticLength(maxDiagnosticLength);
    }

    @Override
    public void execute(ScriptExecution execution) {
        Objects.requireNonNull(execution);
        try {
            executor.execute(() -> executeSafely(execution));
        } catch (RejectedExecutionException exception) {
            throw new ScriptExecutionRejectedException(exception);
        }
    }

    @Override
    public void stop(ScriptExecution execution) {
        Objects.requireNonNull(execution);
        contextRegistry.findByExecutionId(execution.getId())
                .ifPresent(this::stopContext);
    }

    private void executeSafely(ScriptExecution execution) {
        // Prevent unexpected worker failures from leaving an execution permanently RUNNING.
        try {
            executeScript(execution);
        } catch (RuntimeException exception) {
            handleUnexpectedFailure(execution, exception);
        }
    }

    private void executeScript(ScriptExecution execution) {
        // The task may have been stopped while it was still waiting in the executor queue.
        if (!tryStart(execution)) {
            return;
        }
        // Limit the entire RUNNING period, including GraalVM Context initialization.
        ScheduledFuture<?> timeoutTask = scheduleTimeout(execution);
        try (ScriptExecutionOutputStream standardOutput =
                     new ScriptExecutionOutputStream(execution::appendStandardOutput);
                ScriptExecutionOutputStream errorOutput =
                     new ScriptExecutionOutputStream(execution::appendErrorOutput);
                Context context = contextFactory.create(standardOutput, errorOutput)) {
            contextRegistry.register(execution.getId(), context);
            // Stop may happen while the Context is being created, before it is available
            // in the registry.
            if (isCancelled(execution)) {
                return;
            }
            context.eval(GraalScriptContextFactory.LANGUAGE_ID, execution.getBody());
        } catch (PolyglotException exception) {
            // Context.close(true) reports cancellation as PolyglotException. Manually
            // stopped and timed-out executions must retain their final domain status.
            if (isCancelled(execution)) {
                return;
            }
            if (exception.isResourceExhausted()) {
                fail(execution, resourceExhaustionDiagnosticOf(exception));
                return;
            }
            fail(execution, guestDiagnosticOf(exception));
            return;
        } catch (InvalidScriptExecutionStateException exception) {
            ignoreIfCancelled(execution, exception);
            return;
        } finally {
            timeoutTask.cancel(false);
            contextRegistry.unregister(execution.getId());
        }
        complete(execution);
    }

    private void handleUnexpectedFailure(
            ScriptExecution execution,
            RuntimeException exception
    ) {
        log.error("Script execution {} failed unexpectedly", execution.getId(), exception);
        if (execution.getStatus() == ScriptStatus.RUNNING) {
            fail(execution, INTERNAL_ERROR_MESSAGE);
        }
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

    private void fail(ScriptExecution execution, String diagnostic) {
        try {
            execution.fail(clock.instant(), truncateDiagnostic(diagnostic));
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

    private Duration requirePositiveExecutionTimeout(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Script execution timeout must be positive");
        }
        return timeout;
    }

    private int requirePositiveDiagnosticLength(int maxLength) {
        if (maxLength <= 0) {
            throw new IllegalArgumentException("Maximum diagnostic length must be positive");
        }
        return maxLength;
    }

    private String truncateDiagnostic(String diagnostic) {
        Objects.requireNonNull(diagnostic);
        int diagnosticLength = diagnostic.codePointCount(0, diagnostic.length());
        if (diagnosticLength <= maxDiagnosticLength) {
            return diagnostic;
        }

        int retainedCodePoints = maxDiagnosticLength - 1;
        int endIndex = diagnostic.offsetByCodePoints(0, retainedCodePoints);
        return diagnostic.substring(0, endIndex) + TRUNCATION_MARKER;
    }

    private String guestDiagnosticOf(PolyglotException exception) {
        String message = exception.getMessage();
        return message == null ? exception.toString() : message;
    }

    private String resourceExhaustionDiagnosticOf(PolyglotException exception) {
        String detail = exception.getMessage();
        if (detail == null || detail.isBlank()) {
            return RESOURCE_LIMIT_EXCEEDED_MESSAGE;
        }
        return RESOURCE_LIMIT_EXCEEDED_MESSAGE + ": " + detail;
    }

}

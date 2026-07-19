package dev.deschna.scripthub.script.infrastructure.graalvm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.deschna.scripthub.script.application.ScriptExecutionRejectedException;
import dev.deschna.scripthub.script.domain.ScriptExecution;
import dev.deschna.scripthub.script.domain.ScriptStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.unit.DataSize;

class GraalScriptExecutorTest {

    private static final Instant SUBMITTED_AT = Instant.parse("2026-06-14T10:15:30Z");
    private static final Instant EXECUTED_AT = Instant.parse("2026-06-14T10:15:31Z");
    private static final Clock CLOCK = Clock.fixed(EXECUTED_AT, ZoneOffset.UTC);
    private static final long WAIT_TIMEOUT_SECONDS = 5;
    private static final long POLL_INTERVAL_MILLIS = 10;
    private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(10);
    private static final String INTERNAL_ERROR_MESSAGE =
            "Script execution failed due to an internal error";
    private static final GraalScriptSandboxProperties VALID_SANDBOX_PROPERTIES =
            new GraalScriptSandboxProperties(
                    DataSize.ofMegabytes(256),
                    DataSize.ofMegabytes(128),
                    Duration.ofSeconds(15),
                    100,
                    1,
                    DataSize.ofMegabytes(1),
                    DataSize.ofMegabytes(1),
                    100
            );

    private static final String LONG_RUNNING_SCRIPT = """
            console.log('started');
            console.error('warning started');
            const deadline = Date.now() + 2000;
            while (Date.now() < deadline) {}
            console.log('finished');
            console.error('warning finished');
            """;
    private static final ThreadPoolTaskScheduler timeoutScheduler = createTimeoutScheduler();

    @AfterAll
    static void shutDownTimeoutScheduler() {
        timeoutScheduler.destroy();
    }

    @Test
    void completesSuccessfulScriptExecution() {
        ScriptExecution execution = createExecution("console.log('hello')");

        createExecutor(Runnable::run).execute(execution);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.COMPLETED);
        assertThat(execution.getStartedAt()).isEqualTo(EXECUTED_AT);
        assertThat(execution.getFinishedAt()).isEqualTo(EXECUTED_AT);
        assertThat(execution.getStandardOutput().lines()).containsExactly("hello");
        assertThat(execution.getErrorOutput()).isEmpty();
        assertThat(execution.getErrorStackTrace()).isNull();
    }

    @Test
    void capturesErrorOutput() {
        ScriptExecution execution = createExecution("console.error('warning')");

        createExecutor(Runnable::run).execute(execution);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.COMPLETED);
        assertThat(execution.getStandardOutput()).isEmpty();
        assertThat(execution.getErrorOutput().lines()).containsExactly("warning");
    }

    @Test
    void capturesNonAsciiStandardOutput() {
        ScriptExecution execution = createExecution("console.log('こんにちは 👋')");

        createExecutor(Runnable::run).execute(execution);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.COMPLETED);
        assertThat(execution.getStandardOutput().lines()).containsExactly("こんにちは 👋");
    }

    @Test
    void exposesOutputWhileScriptIsRunning() {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        GraalScriptExecutor asyncExecutor = createExecutor(executorService);
        ScriptExecution execution = createExecution(LONG_RUNNING_SCRIPT);

        try {
            asyncExecutor.execute(execution);

            awaitUntil(() -> execution.getStandardOutput().contains("started"));
            awaitUntil(() -> execution.getErrorOutput().contains("warning started"));

            assertThat(execution.getStatus()).isEqualTo(ScriptStatus.RUNNING);
            assertThat(execution.getStandardOutput().lines()).contains("started");
            assertThat(execution.getStandardOutput().lines()).doesNotContain("finished");
            assertThat(execution.getErrorOutput().lines()).contains("warning started");
            assertThat(execution.getErrorOutput().lines()).doesNotContain("warning finished");

            awaitUntil(() -> execution.getStatus() == ScriptStatus.COMPLETED);

            assertThat(execution.getStandardOutput().lines()).contains("finished");
            assertThat(execution.getErrorOutput().lines()).contains("warning finished");
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void stopsRunningScriptExecution() throws Exception {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        GraalScriptExecutor asyncExecutor = createExecutor(executorService);
        ScriptExecution execution = createExecution(LONG_RUNNING_SCRIPT);

        try {
            asyncExecutor.execute(execution);
            awaitUntil(() -> execution.getStandardOutput().contains("started"));
            awaitUntil(() -> execution.getErrorOutput().contains("warning started"));

            execution.stop(EXECUTED_AT);
            asyncExecutor.stop(execution);

            awaitExecutorWorkerAvailable(executorService);

            assertThat(execution.getStatus()).isEqualTo(ScriptStatus.STOPPED);
            assertThat(execution.getFinishedAt()).isEqualTo(EXECUTED_AT);
            assertThat(execution.getStandardOutput().lines()).contains("started");
            assertThat(execution.getStandardOutput().lines()).doesNotContain("finished");
            assertThat(execution.getErrorOutput().lines()).contains("warning started");
            assertThat(execution.getErrorOutput().lines()).doesNotContain("warning finished");
            assertThat(execution.getErrorStackTrace()).isNull();
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void timesOutLongRunningScriptExecution() throws Exception {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        TaskScheduler controlledTimeoutScheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> timeoutTask = mock(ScheduledFuture.class);
        // Capture the timeout callback to trigger it after guest execution starts.
        AtomicReference<Runnable> timeoutAction = new AtomicReference<>();
        when(controlledTimeoutScheduler.getClock()).thenReturn(CLOCK);
        when(controlledTimeoutScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(invocation -> {
                    timeoutAction.set(invocation.getArgument(0));
                    return timeoutTask;
                });
        GraalScriptExecutor timeoutExecutor = new GraalScriptExecutor(
                executorService,
                controlledTimeoutScheduler,
                CLOCK,
                new GraalScriptContextRegistry(),
                new GraalScriptContextFactory(VALID_SANDBOX_PROPERTIES),
                EXECUTION_TIMEOUT
        );
        String infiniteScript = """
                console.log('started');
                while (true) {}
                """;
        ScriptExecution execution = createExecution(infiniteScript);

        try {
            timeoutExecutor.execute(execution);
            awaitUntil(() -> execution.getStandardOutput().contains("started"));

            timeoutAction.get().run();
            awaitExecutorWorkerAvailable(executorService);

            assertThat(execution.getStatus()).isEqualTo(ScriptStatus.TIMED_OUT);
            assertThat(execution.getFinishedAt()).isEqualTo(EXECUTED_AT);
            assertThat(execution.getStandardOutput().lines()).containsExactly("started");
            assertThat(execution.getErrorStackTrace()).isNull();
            verify(controlledTimeoutScheduler).schedule(
                    any(Runnable.class),
                    eq(EXECUTED_AT.plus(EXECUTION_TIMEOUT))
            );
            verify(timeoutTask).cancel(false);
        } finally {
            // Ensure the infinite guest script cannot survive an assertion failure.
            Runnable timeout = timeoutAction.get();
            if (timeout != null) {
                timeout.run();
            }
            timeoutExecutor.stop(execution);
            executorService.shutdownNow();
        }
    }

    @Test
    void ignoresStoppedScriptExecutionBeforeStart() {
        ScriptExecution execution = createExecution("console.log('should not run')");
        execution.stop(EXECUTED_AT);

        createExecutor(Runnable::run).execute(execution);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.STOPPED);
        assertThat(execution.getStartedAt()).isNull();
        assertThat(execution.getStandardOutput()).isEmpty();
    }

    @Test
    void ignoresStoppingScriptExecutionWithoutRegisteredContext() {
        ScriptExecution execution = createExecution("console.log('not running yet')");

        // Executor stops only runtime context, not domain state.
        createExecutor(Runnable::run).stop(execution);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.QUEUED);
        assertThat(execution.getStartedAt()).isNull();
        assertThat(execution.getFinishedAt()).isNull();
    }

    @Test
    void failsBrokenScriptExecution() {
        ScriptExecution execution = createExecution("""
                function fail() {
                    throw new Error('boom');
                }
                fail();
                """);

        createExecutor(Runnable::run).execute(execution);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.FAILED);
        assertThat(execution.getStartedAt()).isEqualTo(EXECUTED_AT);
        assertThat(execution.getFinishedAt()).isEqualTo(EXECUTED_AT);
        assertThat(execution.getErrorStackTrace().lines()).first().asString().contains("boom");
        assertThat(execution.getErrorStackTrace())
                .doesNotContain("dev.deschna", "org.graalvm", "GraalScriptExecutor");
    }

    @Test
    void failsSyntacticallyInvalidScriptExecution() {
        ScriptExecution execution = createExecution("function broken(");

        createExecutor(Runnable::run).execute(execution);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.FAILED);
        assertThat(execution.getStartedAt()).isEqualTo(EXECUTED_AT);
        assertThat(execution.getFinishedAt()).isEqualTo(EXECUTED_AT);
        assertThat(execution.getErrorStackTrace()).contains("SyntaxError");
        assertThat(execution.getErrorStackTrace())
                .doesNotContain("dev.deschna", "org.graalvm", "GraalScriptExecutor");
    }

    @Test
    void failsScriptExecutionWhenContextCreationFails() {
        GraalScriptContextFactory failingContextFactory = mock(GraalScriptContextFactory.class);
        when(failingContextFactory.create(any(), any()))
                .thenThrow(new IllegalStateException("Context creation failed"));
        GraalScriptExecutor failingExecutor = new GraalScriptExecutor(
                Runnable::run,
                timeoutScheduler,
                CLOCK,
                new GraalScriptContextRegistry(),
                failingContextFactory,
                EXECUTION_TIMEOUT
        );
        ScriptExecution execution = createExecution("console.log('should not run')");

        failingExecutor.execute(execution);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.FAILED);
        assertThat(execution.getStartedAt()).isEqualTo(EXECUTED_AT);
        assertThat(execution.getFinishedAt()).isEqualTo(EXECUTED_AT);
        assertThat(execution.getErrorStackTrace())
                .isEqualTo(INTERNAL_ERROR_MESSAGE);
    }

    @Test
    void failsScriptExecutionWhenTimeoutSchedulingFails() {
        TaskScheduler failingTimeoutScheduler = mock(TaskScheduler.class);
        when(failingTimeoutScheduler.getClock()).thenReturn(CLOCK);
        when(failingTimeoutScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenThrow(new IllegalStateException("Timeout scheduling failed"));
        GraalScriptExecutor failingExecutor = new GraalScriptExecutor(
                Runnable::run,
                failingTimeoutScheduler,
                CLOCK,
                new GraalScriptContextRegistry(),
                new GraalScriptContextFactory(VALID_SANDBOX_PROPERTIES),
                EXECUTION_TIMEOUT
        );
        ScriptExecution execution = createExecution("console.log('should not run')");

        failingExecutor.execute(execution);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.FAILED);
        assertThat(execution.getStartedAt()).isEqualTo(EXECUTED_AT);
        assertThat(execution.getFinishedAt()).isEqualTo(EXECUTED_AT);
        assertThat(execution.getErrorStackTrace())
                .isEqualTo(INTERNAL_ERROR_MESSAGE);
    }

    @Test
    void rejectsMissingScriptExecution() {
        assertThatNullPointerException()
                .isThrownBy(() -> createExecutor(Runnable::run).execute(null));
    }

    @Test
    void translatesExecutorRejection() {
        RejectedExecutionException rejection = new RejectedExecutionException("Queue is full");
        GraalScriptExecutor rejectingExecutor = createExecutor(command -> {
            throw rejection;
        });
        ScriptExecution execution = createExecution("console.log('should not run')");

        assertThatExceptionOfType(ScriptExecutionRejectedException.class)
                .isThrownBy(() -> rejectingExecutor.execute(execution))
                .withMessage("Script execution is temporarily unavailable")
                .withCause(rejection);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.QUEUED);
    }

    @Test
    void rejectsMissingScriptExecutionWhenStopping() {
        assertThatNullPointerException()
                .isThrownBy(() -> createExecutor(Runnable::run).stop(null));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveExecutionTimeout(long timeoutSeconds) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GraalScriptExecutor(
                        Runnable::run,
                        timeoutScheduler,
                        CLOCK,
                        new GraalScriptContextRegistry(),
                        new GraalScriptContextFactory(VALID_SANDBOX_PROPERTIES),
                        Duration.ofSeconds(timeoutSeconds)
                ));
    }

    private ScriptExecution createExecution(String body) {
        return ScriptExecution.create(body, SUBMITTED_AT);
    }

    private GraalScriptExecutor createExecutor(Executor taskExecutor) {
        return new GraalScriptExecutor(
                taskExecutor,
                timeoutScheduler,
                CLOCK,
                new GraalScriptContextRegistry(),
                new GraalScriptContextFactory(VALID_SANDBOX_PROPERTIES),
                EXECUTION_TIMEOUT
        );
    }

    private static ThreadPoolTaskScheduler createTimeoutScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    private void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition was not met before timeout");
            }
            sleepBriefly();
        }
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for condition", exception);
        }
    }

    private void awaitExecutorWorkerAvailable(ExecutorService executorService) throws Exception {
        Future<?> nextTask = executorService.submit(() -> {
        });
        nextTask.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}

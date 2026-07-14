package dev.deschna.scripthub.script.infrastructure.graalvm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.deschna.scripthub.script.domain.ScriptExecution;
import dev.deschna.scripthub.script.domain.ScriptStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

class GraalScriptExecutorTest {

    private static final Instant SUBMITTED_AT = Instant.parse("2026-06-14T10:15:30Z");
    private static final Instant EXECUTED_AT = Instant.parse("2026-06-14T10:15:31Z");
    private static final Clock CLOCK = Clock.fixed(EXECUTED_AT, ZoneOffset.UTC);
    private static final long WAIT_TIMEOUT_SECONDS = 5;
    private static final long POLL_INTERVAL_MILLIS = 10;
    private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(10);

    private static final String LONG_RUNNING_SCRIPT = """
            console.log('started');
            console.error('warning started');
            const deadline = Date.now() + 2000;
            while (Date.now() < deadline) {}
            console.log('finished');
            console.error('warning finished');
            """;
    private static final String INFINITE_SCRIPT = """
            console.log('started');
            while (true) {}
            """;

    private static final ThreadPoolTaskScheduler timeoutScheduler = createTimeoutScheduler();

    private final GraalScriptExecutor executor =
            new GraalScriptExecutor(
                    Runnable::run,
                    timeoutScheduler,
                    CLOCK,
                    new GraalScriptContextRegistry(),
                    EXECUTION_TIMEOUT
            );

    @AfterAll
    static void shutDownTimeoutScheduler() {
        timeoutScheduler.destroy();
    }

    @Test
    void completesSuccessfulScriptExecution() {
        ScriptExecution execution = createExecution("console.log('hello')");

        executor.execute(execution);

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

        executor.execute(execution);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.COMPLETED);
        assertThat(execution.getStandardOutput()).isEmpty();
        assertThat(execution.getErrorOutput().lines()).containsExactly("warning");
    }

    @Test
    void capturesNonAsciiStandardOutput() {
        ScriptExecution execution = createExecution("console.log('こんにちは 👋')");

        executor.execute(execution);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.COMPLETED);
        assertThat(execution.getStandardOutput().lines()).containsExactly("こんにちは 👋");
    }

    @Test
    void exposesOutputWhileScriptIsRunning() {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        GraalScriptExecutor asyncExecutor = createAsyncExecutor(executorService);
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
        GraalScriptExecutor asyncExecutor = createAsyncExecutor(executorService);
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
        TaskScheduler timeoutScheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> timeoutTask = mock(ScheduledFuture.class);
        // Capture the timeout callback to trigger it after guest execution starts.
        AtomicReference<Runnable> timeoutAction = new AtomicReference<>();
        when(timeoutScheduler.getClock()).thenReturn(CLOCK);
        when(timeoutScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(invocation -> {
                    timeoutAction.set(invocation.getArgument(0));
                    return timeoutTask;
                });
        GraalScriptExecutor timeoutExecutor = new GraalScriptExecutor(
                executorService,
                timeoutScheduler,
                CLOCK,
                new GraalScriptContextRegistry(),
                EXECUTION_TIMEOUT
        );
        ScriptExecution execution = createExecution(INFINITE_SCRIPT);

        try {
            timeoutExecutor.execute(execution);
            awaitUntil(() -> execution.getStandardOutput().contains("started"));

            timeoutAction.get().run();
            awaitExecutorWorkerAvailable(executorService);

            assertThat(execution.getStatus()).isEqualTo(ScriptStatus.TIMED_OUT);
            assertThat(execution.getFinishedAt()).isEqualTo(EXECUTED_AT);
            assertThat(execution.getStandardOutput().lines()).containsExactly("started");
            assertThat(execution.getErrorStackTrace()).isNull();
            verify(timeoutScheduler).schedule(
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

        executor.execute(execution);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.STOPPED);
        assertThat(execution.getStartedAt()).isNull();
        assertThat(execution.getStandardOutput()).isEmpty();
    }

    @Test
    void ignoresStoppingScriptExecutionWithoutRegisteredContext() {
        ScriptExecution execution = createExecution("console.log('not running yet')");

        // Executor stops only runtime context, not domain state.
        executor.stop(execution);

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

        executor.execute(execution);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.FAILED);
        assertThat(execution.getStartedAt()).isEqualTo(EXECUTED_AT);
        assertThat(execution.getFinishedAt()).isEqualTo(EXECUTED_AT);
        assertThat(execution.getErrorStackTrace().lines()).first().asString().contains("boom");
        assertThat(execution.getErrorStackTrace().lines())
                .anyMatch(line -> line.startsWith("\tat ") && line.contains("fail"));
        assertThat(execution.getErrorStackTrace())
                .doesNotContain("dev.deschna", "org.graalvm", "GraalScriptExecutor");
    }

    @Test
    void failsSyntacticallyInvalidScriptExecution() {
        ScriptExecution execution = createExecution("function broken(");

        executor.execute(execution);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.FAILED);
        assertThat(execution.getStartedAt()).isEqualTo(EXECUTED_AT);
        assertThat(execution.getFinishedAt()).isEqualTo(EXECUTED_AT);
        assertThat(execution.getErrorStackTrace()).contains("SyntaxError");
        assertThat(execution.getErrorStackTrace())
                .doesNotContain("dev.deschna", "org.graalvm", "GraalScriptExecutor");
    }

    @Test
    void rejectsMissingScriptExecution() {
        assertThatNullPointerException()
                .isThrownBy(() -> executor.execute(null));
    }

    @Test
    void rejectsMissingScriptExecutionWhenStopping() {
        assertThatNullPointerException()
                .isThrownBy(() -> executor.stop(null));
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
                        Duration.ofSeconds(timeoutSeconds)
                ));
    }

    private ScriptExecution createExecution(String body) {
        return ScriptExecution.create(body, SUBMITTED_AT);
    }

    private GraalScriptExecutor createAsyncExecutor(ExecutorService executorService) {
        return new GraalScriptExecutor(
                executorService,
                timeoutScheduler,
                CLOCK,
                new GraalScriptContextRegistry(),
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

package dev.deschna.scripthub.script.infrastructure.graalvm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import dev.deschna.scripthub.script.domain.ScriptExecution;
import dev.deschna.scripthub.script.domain.ScriptStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class GraalScriptExecutorTest {

    private static final Instant SUBMITTED_AT = Instant.parse("2026-06-14T10:15:30Z");
    private static final Instant EXECUTED_AT = Instant.parse("2026-06-14T10:15:31Z");
    private static final Clock CLOCK = Clock.fixed(EXECUTED_AT, ZoneOffset.UTC);
    private static final long WAIT_TIMEOUT_SECONDS = 5;
    private static final long POLL_INTERVAL_MILLIS = 10;
    private static final String LONG_RUNNING_SCRIPT = """
            console.log('started');
            console.error('warning started');
            const deadline = Date.now() + 2000;
            while (Date.now() < deadline) {}
            console.log('finished');
            console.error('warning finished');
            """;

    private final GraalScriptExecutor executor = new GraalScriptExecutor(Runnable::run, CLOCK);

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
        GraalScriptExecutor asyncExecutor = new GraalScriptExecutor(executorService, CLOCK);
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

    private ScriptExecution createExecution(String body) {
        return ScriptExecution.create(body, SUBMITTED_AT);
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
}

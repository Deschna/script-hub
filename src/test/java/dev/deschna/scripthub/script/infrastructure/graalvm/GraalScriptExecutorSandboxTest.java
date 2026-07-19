package dev.deschna.scripthub.script.infrastructure.graalvm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.deschna.scripthub.script.domain.ScriptExecution;
import dev.deschna.scripthub.script.domain.ScriptStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.unit.DataSize;

class GraalScriptExecutorSandboxTest {

    private static final Instant EXECUTED_AT = Instant.parse("2026-06-14T10:15:31Z");
    private static final Clock CLOCK = Clock.fixed(EXECUTED_AT, ZoneOffset.UTC);
    private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(10);
    private static final long OUTPUT_LIMIT_BYTES = 64;
    private static final String RESOURCE_LIMIT_EXCEEDED_PREFIX =
            "Script execution resource limit exceeded:";
    private static final ThreadPoolTaskScheduler timeoutScheduler = createTimeoutScheduler();

    @AfterAll
    static void shutDownTimeoutScheduler() {
        timeoutScheduler.destroy();
    }

    @Test
    void doesNotExposeHostCapabilitiesToScript() {
        ScriptExecution execution = createExecution("""
                console.log(typeof Java);
                console.log(typeof Polyglot);
                console.log(typeof process);
                console.log(typeof require);
                """);

        createExecutor(validSandboxProperties()).execute(execution);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.COMPLETED);
        assertThat(execution.getStandardOutput().lines())
                .containsExactly("undefined", "undefined", "undefined", "undefined");
    }

    @Test
    void doesNotAllowReadingHostFiles(@TempDir Path tempDirectory) throws IOException {
        Path hostScript = tempDirectory.resolve("host-script.js");
        Files.writeString(hostScript, "console.log('sandbox escaped')", StandardCharsets.UTF_8);
        ScriptExecution execution = createExecution("""
                try {
                    load('%s');
                } catch (error) {
                    console.log('access denied');
                }
                """.formatted(hostScript.toUri()));

        createExecutor(validSandboxProperties()).execute(execution);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.COMPLETED);
        assertThat(execution.getStandardOutput().lines()).containsExactly("access denied");
    }

    @Test
    void failsScriptExecutionWhenSandboxOutputLimitIsExceeded() {
        GraalScriptSandboxProperties properties = sandboxProperties(
                Duration.ofSeconds(15),
                DataSize.ofBytes(OUTPUT_LIMIT_BYTES),
                DataSize.ofMegabytes(1)
        );
        ScriptExecution execution = createExecution("""
                while (true) {
                    console.log('output that exceeds the configured sandbox limit');
                }
                """);

        createExecutor(properties).execute(execution);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.FAILED);
        assertThat(execution.getErrorStackTrace())
                .startsWith(RESOURCE_LIMIT_EXCEEDED_PREFIX)
                .containsIgnoringCase("output");
        assertThat(execution.getStandardOutput().getBytes(StandardCharsets.UTF_8))
                .hasSizeLessThanOrEqualTo((int) OUTPUT_LIMIT_BYTES);
    }

    @Test
    void failsScriptExecutionWhenSandboxErrorLimitIsExceeded() {
        GraalScriptSandboxProperties properties = sandboxProperties(
                Duration.ofSeconds(15),
                DataSize.ofMegabytes(1),
                DataSize.ofBytes(OUTPUT_LIMIT_BYTES)
        );
        ScriptExecution execution = createExecution("""
                while (true) {
                    console.error('error that exceeds the configured sandbox limit');
                }
                """);

        createExecutor(properties).execute(execution);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.FAILED);
        assertThat(execution.getErrorStackTrace())
                .startsWith(RESOURCE_LIMIT_EXCEEDED_PREFIX)
                .containsIgnoringCase("error");
        assertThat(execution.getErrorOutput().getBytes(StandardCharsets.UTF_8))
                .hasSizeLessThanOrEqualTo((int) OUTPUT_LIMIT_BYTES);
    }

    @Test
    void failsScriptExecutionWhenSandboxCpuLimitIsExceeded() {
        GraalScriptSandboxProperties properties = sandboxProperties(
                Duration.ofMillis(20),
                DataSize.ofMegabytes(1),
                DataSize.ofMegabytes(1)
        );
        ScriptExecution execution = createExecution("while (true) {}");

        createExecutor(properties).execute(execution);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.FAILED);
        assertThat(execution.getErrorStackTrace())
                .startsWith(RESOURCE_LIMIT_EXCEEDED_PREFIX)
                .containsIgnoringCase("CPU");
    }

    private GraalScriptExecutor createExecutor(GraalScriptSandboxProperties properties) {
        return new GraalScriptExecutor(
                Runnable::run,
                timeoutScheduler,
                CLOCK,
                new GraalScriptContextRegistry(),
                new GraalScriptContextFactory(properties),
                EXECUTION_TIMEOUT
        );
    }

    private ScriptExecution createExecution(String body) {
        return ScriptExecution.create(body, EXECUTED_AT);
    }

    private static GraalScriptSandboxProperties validSandboxProperties() {
        return sandboxProperties(
                Duration.ofSeconds(15),
                DataSize.ofMegabytes(1),
                DataSize.ofMegabytes(1)
        );
    }

    private static GraalScriptSandboxProperties sandboxProperties(
            Duration maxCpuTime,
            DataSize maxOutputSize,
            DataSize maxErrorSize
    ) {
        return new GraalScriptSandboxProperties(
                DataSize.ofMegabytes(256),
                DataSize.ofMegabytes(128),
                maxCpuTime,
                100,
                1,
                maxOutputSize,
                maxErrorSize,
                100
        );
    }

    private static ThreadPoolTaskScheduler createTimeoutScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }
}

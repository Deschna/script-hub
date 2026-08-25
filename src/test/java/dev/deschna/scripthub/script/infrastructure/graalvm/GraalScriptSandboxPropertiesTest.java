package dev.deschna.scripthub.script.infrastructure.graalvm;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.util.unit.DataSize;

class GraalScriptSandboxPropertiesTest {

    private static final DataSize MEMORY_LIMIT = DataSize.ofMegabytes(128);
    private static final DataSize OUTPUT_LIMIT = DataSize.ofMegabytes(1);
    private static final Duration CPU_TIME_LIMIT = Duration.ofSeconds(30);
    private static final int STRUCTURAL_LIMIT = 100;
    private static final int THREAD_LIMIT = 1;

    @ParameterizedTest
    @EnumSource(SandboxLimit.class)
    void rejectsInvalidSandboxLimits(SandboxLimit limit) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> createPropertiesWithInvalid(limit));
    }

    private void createPropertiesWithInvalid(SandboxLimit limit) {
        DataSize maxIsolateMemory = MEMORY_LIMIT;
        DataSize maxHeapMemory = MEMORY_LIMIT;
        Duration maxCpuTime = CPU_TIME_LIMIT;
        int maxAstDepth = STRUCTURAL_LIMIT;
        int maxThreads = THREAD_LIMIT;
        DataSize maxOutputSize = OUTPUT_LIMIT;
        DataSize maxErrorSize = OUTPUT_LIMIT;
        int maxStackFrames = STRUCTURAL_LIMIT;

        switch (limit) {
            case ISOLATE_MEMORY -> maxIsolateMemory = DataSize.ofBytes(0);
            case HEAP_MEMORY -> maxHeapMemory = DataSize.ofBytes(0);
            // A positive sub-millisecond duration becomes zero in the GraalVM option format.
            case CPU_TIME -> maxCpuTime = Duration.ofNanos(1);
            case AST_DEPTH -> maxAstDepth = 0;
            case THREADS -> maxThreads = 0;
            case OUTPUT_SIZE -> maxOutputSize = DataSize.ofBytes(0);
            case ERROR_SIZE -> maxErrorSize = DataSize.ofBytes(0);
            case STACK_FRAMES -> maxStackFrames = 0;
            default -> throw new AssertionError("Unhandled sandbox limit: " + limit);
        }

        new GraalScriptSandboxProperties(
                maxIsolateMemory,
                maxHeapMemory,
                maxCpuTime,
                maxAstDepth,
                maxThreads,
                maxOutputSize,
                maxErrorSize,
                maxStackFrames
        );
    }

    private enum SandboxLimit {
        ISOLATE_MEMORY,
        HEAP_MEMORY,
        CPU_TIME,
        AST_DEPTH,
        THREADS,
        OUTPUT_SIZE,
        ERROR_SIZE,
        STACK_FRAMES
    }
}

package dev.deschna.scripthub.script.infrastructure.graalvm;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("script-hub.execution.sandbox")
public record GraalScriptSandboxProperties(
        DataSize maxIsolateMemory,
        DataSize maxHeapMemory,
        Duration maxCpuTime,
        int maxAstDepth,
        int maxThreads,
        DataSize maxOutputSize,
        DataSize maxErrorSize,
        int maxStackFrames
) {

    public GraalScriptSandboxProperties {
        requirePositive(maxIsolateMemory, "Maximum isolate memory");
        requirePositive(maxHeapMemory, "Maximum heap memory");
        requirePositiveCpuTime(maxCpuTime);
        requirePositive(maxAstDepth, "Maximum AST depth");
        requirePositive(maxThreads, "Maximum thread count");
        requirePositive(maxOutputSize, "Maximum output size");
        requirePositive(maxErrorSize, "Maximum error size");
        requirePositive(maxStackFrames, "Maximum stack frame count");
    }

    private static void requirePositive(DataSize value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.toBytes() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePositiveCpuTime(Duration value) {
        Objects.requireNonNull(value, "Maximum CPU time must not be null");
        if (value.toMillis() <= 0) {
            throw new IllegalArgumentException(
                    "Maximum CPU time must be positive at millisecond precision"
            );
        }
    }
}

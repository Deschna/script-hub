package dev.deschna.scripthub.script.infrastructure.graalvm;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.SandboxPolicy;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

@Component
@RequiredArgsConstructor
class GraalScriptContextFactory {

    // Shared with the executor so Context creation and source evaluation use the same language.
    static final String LANGUAGE_ID = "js";
    private static final String MAX_ISOLATE_MEMORY_OPTION = "engine.MaxIsolateMemory";
    private static final String MAX_HEAP_MEMORY_OPTION = "sandbox.MaxHeapMemory";
    private static final String MAX_CPU_TIME_OPTION = "sandbox.MaxCPUTime";
    private static final String MAX_AST_DEPTH_OPTION = "sandbox.MaxASTDepth";
    private static final String MAX_THREADS_OPTION = "sandbox.MaxThreads";
    private static final String MAX_OUTPUT_SIZE_OPTION = "sandbox.MaxOutputStreamSize";
    private static final String MAX_ERROR_SIZE_OPTION = "sandbox.MaxErrorStreamSize";
    private static final String MAX_STACK_FRAMES_OPTION = "sandbox.MaxStackFrames";

    @NonNull
    private final GraalScriptSandboxProperties properties;

    Context create(OutputStream standardOutput, OutputStream errorOutput) {
        return sandboxedContextBuilder()
                .out(Objects.requireNonNull(standardOutput))
                .err(Objects.requireNonNull(errorOutput))
                .build();
    }

    @PostConstruct
    void validateSandbox() {
        // Fail application startup if the runtime or configured limits cannot provide the
        // security boundary required for user-supplied scripts.
        try (Context context = create(
                OutputStream.nullOutputStream(),
                OutputStream.nullOutputStream()
        )) {
            // Execute a harmless expression to force JavaScript runtime initialization.
            context.eval(LANGUAGE_ID, "0");
        }
    }

    private Context.Builder sandboxedContextBuilder() {
        return Context.newBuilder(LANGUAGE_ID)
                .sandbox(SandboxPolicy.UNTRUSTED)
                .in(InputStream.nullInputStream())
                .option(MAX_ISOLATE_MEMORY_OPTION, bytes(properties.maxIsolateMemory()))
                .option(MAX_HEAP_MEMORY_OPTION, bytes(properties.maxHeapMemory()))
                .option(MAX_CPU_TIME_OPTION, duration(properties.maxCpuTime()))
                .option(MAX_AST_DEPTH_OPTION, Integer.toString(properties.maxAstDepth()))
                .option(MAX_THREADS_OPTION, Integer.toString(properties.maxThreads()))
                .option(MAX_OUTPUT_SIZE_OPTION, bytes(properties.maxOutputSize()))
                .option(MAX_ERROR_SIZE_OPTION, bytes(properties.maxErrorSize()))
                .option(MAX_STACK_FRAMES_OPTION, Integer.toString(properties.maxStackFrames()));
    }

    private static String bytes(DataSize size) {
        return size.toBytes() + "B";
    }

    private static String duration(Duration duration) {
        return duration.toMillis() + "ms";
    }
}

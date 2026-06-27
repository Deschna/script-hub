package dev.deschna.scripthub.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ScriptExecutorConfigurationTest {

    private static final int MIN_SCRIPT_EXECUTION_THREADS = 1;
    private static final int RESERVED_APPLICATION_THREADS = 2;
    private static final int QUEUED_TASKS_PER_WORKER = 4;
    private static final String THREAD_NAME_PREFIX = "script-exec-";

    @Test
    void createsScriptExecutionTaskExecutorWithExpectedPolicy() {
        ScriptExecutorConfiguration configuration = new ScriptExecutorConfiguration();

        ThreadPoolTaskExecutor executor = configuration.scriptExecutionTaskExecutor();

        try {
            executor.initialize();
            ThreadPoolExecutor threadPoolExecutor = executor.getThreadPoolExecutor();
            int expectedThreadCount = expectedThreadCount();
            int expectedQueueCapacity = expectedThreadCount * QUEUED_TASKS_PER_WORKER;

            assertThat(threadPoolExecutor.getCorePoolSize()).isEqualTo(expectedThreadCount);
            assertThat(threadPoolExecutor.getMaximumPoolSize()).isEqualTo(expectedThreadCount);
            assertThat(threadPoolExecutor.getQueue().remainingCapacity())
                    .isEqualTo(expectedQueueCapacity);
            assertThat(executor.getThreadNamePrefix()).isEqualTo(THREAD_NAME_PREFIX);
        } finally {
            executor.shutdown();
        }
    }

    private int expectedThreadCount() {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        return Math.max(
                MIN_SCRIPT_EXECUTION_THREADS,
                availableProcessors - RESERVED_APPLICATION_THREADS
        );
    }
}

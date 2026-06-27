package dev.deschna.scripthub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ScriptExecutorConfiguration {

    private static final int MIN_SCRIPT_EXECUTION_THREADS = 1;
    private static final int RESERVED_APPLICATION_THREADS = 2;
    private static final int QUEUED_TASKS_PER_WORKER = 4;
    private static final String SCRIPT_EXECUTION_THREAD_NAME_PREFIX = "script-exec-";

    @Bean
    public ThreadPoolTaskExecutor scriptExecutionTaskExecutor() {
        int threadCount = scriptExecutionThreadCount();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Keep the pool fixed-size because extra CPU-bound script workers would mostly
        // compete for CPU instead of improving throughput.
        executor.setCorePoolSize(threadCount);
        executor.setMaxPoolSize(threadCount);
        executor.setQueueCapacity(scriptExecutionQueueCapacity(threadCount));
        executor.setThreadNamePrefix(SCRIPT_EXECUTION_THREAD_NAME_PREFIX);
        return executor;
    }

    private int scriptExecutionThreadCount() {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        return Math.max(
                MIN_SCRIPT_EXECUTION_THREADS,
                availableProcessors - RESERVED_APPLICATION_THREADS
        );
    }

    private int scriptExecutionQueueCapacity(int threadCount) {
        return threadCount * QUEUED_TASKS_PER_WORKER;
    }
}

package dev.deschna.scripthub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class ScriptExecutorConfiguration {

    private static final int MIN_SCRIPT_EXECUTION_THREADS = 1;
    private static final int RESERVED_APPLICATION_THREADS = 2;
    private static final int QUEUED_TASKS_PER_WORKER = 4;
    private static final int SCRIPT_TIMEOUT_SCHEDULER_THREADS = 1;
    private static final String SCRIPT_EXECUTION_THREAD_NAME_PREFIX = "script-exec-";
    private static final String SCRIPT_TIMEOUT_THREAD_NAME_PREFIX = "script-timeout-";

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

    @Bean
    public ThreadPoolTaskScheduler scriptTimeoutTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(SCRIPT_TIMEOUT_SCHEDULER_THREADS);
        scheduler.setThreadNamePrefix(SCRIPT_TIMEOUT_THREAD_NAME_PREFIX);
        // Keep timeout tasks alive while running scripts finish during graceful shutdown.
        scheduler.setDaemon(false);
        // Completed scripts cancel their timeout task. Remove it immediately instead of
        // retaining it in the delay queue until the original deadline.
        scheduler.setRemoveOnCancelPolicy(true);
        // Running scripts still need their timeout tasks during graceful shutdown.
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(true);
        return scheduler;
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

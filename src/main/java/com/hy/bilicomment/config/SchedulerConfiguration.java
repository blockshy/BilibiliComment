package com.hy.bilicomment.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SchedulerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SchedulerConfiguration.class);

    @Bean
    ThreadPoolTaskScheduler taskScheduler(AppProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.getScheduler().getPoolSize());
        scheduler.setThreadNamePrefix("task-scheduler-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        // Scheduled callbacks only coordinate durable work. Interrupt them on shutdown instead
        // of waiting for callbacks that Spring has already paused during lifecycle stop.
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(20);
        scheduler.setErrorHandler(error -> log.error("Scheduled task failed", error));
        return scheduler;
    }

    @Bean
    @Primary
    ThreadPoolTaskExecutor taskWorkerExecutor(AppProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getScheduler().getWorkerCoreSize());
        executor.setMaxPoolSize(properties.getScheduler().getWorkerMaxSize());
        executor.setQueueCapacity(properties.getScheduler().getWorkerQueueCapacity());
        executor.setThreadNamePrefix("task-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }

    @Bean
    ThreadPoolTaskExecutor backfillTaskExecutor(AppProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int concurrency = properties.getScheduler().getBackfillConcurrency();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(properties.getScheduler().getBackfillQueueCapacity());
        executor.setThreadNamePrefix("backfill-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }
}

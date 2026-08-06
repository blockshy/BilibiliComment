package com.hy.bilicomment.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class CommentExportConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CommentExportConfiguration.class);

    @Bean
    TaskScheduler commentExportScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("comment-export-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        // Export jobs are leased and recoverable. Interrupt scheduler callbacks on shutdown so
        // a callback paused by Spring's lifecycle cannot keep the JVM alive until the timeout.
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(20);
        scheduler.setErrorHandler(error -> log.error("Comment export scheduling failed", error));
        return scheduler;
    }
}

package com.hy.bilicomment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class TaskSchedulerShutdownTests {

    @Test
    void commentExportSchedulerInterruptsCallbackPausedDuringLifecycleStop() throws Exception {
        ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler)
                new CommentExportConfiguration().commentExportScheduler();

        assertPromptShutdownOfPausedCallback(scheduler);
    }

    @Test
    void taskSchedulerInterruptsCallbackPausedDuringLifecycleStop() throws Exception {
        ThreadPoolTaskScheduler scheduler =
                new SchedulerConfiguration().taskScheduler(new AppProperties());

        assertPromptShutdownOfPausedCallback(scheduler);
    }

    private void assertPromptShutdownOfPausedCallback(ThreadPoolTaskScheduler scheduler)
            throws Exception {
        scheduler.initialize();
        ScheduledThreadPoolExecutor executor = scheduler.getScheduledThreadPoolExecutor();
        AtomicBoolean callbackRan = new AtomicBoolean();
        try {
            scheduler.start();
            scheduler.stop();
            scheduler.schedule(() -> callbackRan.set(true), Instant.now());
            awaitActiveCallback(executor);

            assertThat(executor.getActiveCount()).isPositive();
            assertTimeoutPreemptively(Duration.ofSeconds(2), scheduler::destroy);

            assertThat(callbackRan).isFalse();
            assertThat(executor.isTerminated()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    private void awaitActiveCallback(ScheduledThreadPoolExecutor executor)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (executor.getActiveCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }
}

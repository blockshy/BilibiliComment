package com.hy.bilicomment.config;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebAsyncConfiguration implements WebMvcConfigurer {

    private static final Duration STREAMING_TIMEOUT = Duration.ofMinutes(30);

    private final ThreadPoolTaskExecutor downloadStreamingExecutor;

    public WebAsyncConfiguration(
            @Qualifier("downloadStreamingExecutor") ThreadPoolTaskExecutor downloadStreamingExecutor) {
        this.downloadStreamingExecutor = downloadStreamingExecutor;
    }

    @Bean
    static ThreadPoolTaskExecutor downloadStreamingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("download-stream-");
        // Apply bounded backpressure without dropping a response whose file is already open.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(downloadStreamingExecutor);
        configurer.setDefaultTimeout(STREAMING_TIMEOUT.toMillis());
    }
}

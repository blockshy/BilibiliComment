package com.hy.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.Executors;

@Configuration
public class TaskExecutorConfig implements SchedulingConfigurer {

    /*// 定义一个线程池任务执行器的配置Bean
    @Bean
    public ThreadPoolTaskExecutor taskExecutor() {
        // 创建一个新的线程池任务执行器实例
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 设置核心线程池大小为100
        executor.setCorePoolSize(100);

        // 设置最大线程池大小为200
        executor.setMaxPoolSize(200);

        // 设置线程池的队列容量为1000
        executor.setQueueCapacity(1000);

        // 设置线程空闲时间为60秒
        executor.setKeepAliveSeconds(60);

        // 设置线程名称前缀为"task-executor-"
        executor.setThreadNamePrefix("task-executor-");

        // 返回配置好的线程池任务执行器实例
        return executor;
    }*/

    @Override
    public void configureTasks(ScheduledTaskRegistrar scheduledTaskRegistrar) {
        scheduledTaskRegistrar.setScheduler(Executors.newScheduledThreadPool(200));
    }
}
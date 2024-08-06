package com.hy.schedule;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Component
public class ThreadPoolMonitoring {

    private final Executor taskExecutor;

    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    private static final Logger logger = LogManager.getLogger("ThreadLogFile");
    //private static final Logger logger = LogManager.getLogger(ThreadPoolMonitoring.class);


    @Autowired
    public ThreadPoolMonitoring(@Qualifier("taskExecutor") Executor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    //监控SYNC线程池
    @Scheduled(fixedRate = 1000) // 每秒执行一次
    public void monitorThreadPool() {
        ThreadPoolExecutor executor = ((ThreadPoolTaskExecutor)taskExecutor).getThreadPoolExecutor();
        if (executor == null) {
            return;
        }
        logger.info("===== 线程池信息 =====");
        logger.info("核心线程数：" + executor.getCorePoolSize());
        logger.info("最大线程数：" + executor.getMaximumPoolSize());
        logger.info("当前线程数：" + executor.getPoolSize());
        logger.info("活动线程数：" + executor.getActiveCount());
        logger.info("队列大小：" + executor.getQueue().size());
        logger.info("已完成任务数：" + executor.getCompletedTaskCount());
        logger.info("总任务数：" + executor.getTaskCount());
        logger.info("=====================");
    }

    @Scheduled(fixedRate = 1000) // 每隔1秒执行一次
    public void printThreadCount() {
        int threadCount = threadBean.getThreadCount();
        logger.info("==== 服务线程池信息 ====");
        logger.info("服务当前线程数量：" + threadCount);
        logger.info("=====================");
    }


}

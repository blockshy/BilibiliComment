package com.hy.schedule;

import com.hy.common.EnabledEnum;
import com.hy.component.CommentComponent;
import com.hy.mybatis.entity.ScheduledTask;
import com.hy.mybatis.mapper.ScheduledTaskMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

@Component
public class DynamicScheduler {

    private static final Logger logger = LogManager.getLogger("InfoLogFile");

    @Resource
    private ScheduledTaskMapper taskMapper;

    @Resource
    private CommentComponent component;

    private final Map<String, ScheduledFuture<?>> scheduledTasks = new HashMap<>();
    private final TaskScheduler taskScheduler = new ThreadPoolTaskScheduler();

    //初始化任务列表
    //@PostConstruct
    public void init() {
        ((ThreadPoolTaskScheduler) taskScheduler).initialize();
        loadAndScheduleTasks();
    }

    //每十秒自动读取数据库刷新任务列表
    //@Scheduled(fixedRate = 10000) // 每分钟检查一次
    public void refreshTasks() {
        logger.info("-----refreshTaskList");
        loadAndScheduleTasks();
    }

    public void loadAndScheduleTasks() {
        // 取消已调度的任务
        cancelAllTasks();

        // 从数据库加载定时任务配置
        List<ScheduledTask> tasks = taskMapper.findAll();
        tasks.forEach(this::scheduleTask);
    }

    private void scheduleTask(ScheduledTask task) {
        if (task.getEnabled() == null || EnabledEnum.DISABLE.getValue() == task.getEnabled()) {
            return;
        }

        Runnable taskRunnable = () -> {
            // 执行任务的逻辑
            logger.info("=================================");
            logger.info("Executing task: " + task.getTaskName());
            if(EnabledEnum.ENABLED.getValue() == task.getEnabled()){
                if(1 == task.getType()){
                    component.getVideoNewComment(task);
                }
                if(2 == task.getType()){
                    component.getMarkNewComment(task);
                }
            }

            // 创建 CronTrigger
            CronTrigger cronTrigger = new CronTrigger(task.getCronExpression());

            // 获取当前时间
            LocalDateTime now = LocalDateTime.now();
            ZonedDateTime zonedNow = now.atZone(ZoneId.systemDefault());
            Date nowDate = Date.from(zonedNow.toInstant());

            // 使用 Trigger.nextExecutionTime 来计算下一个执行时间
            Date nextExecutionDate = cronTrigger.nextExecutionTime(new TriggerContextImpl(nowDate));
            ZonedDateTime nextExecutionZoned = nextExecutionDate.toInstant().atZone(ZoneId.systemDefault());
            LocalDateTime nextExecutionLocal = nextExecutionZoned.toLocalDateTime();

            task.setLastExecution(now);
            task.setNextExecution(nextExecutionLocal);
            taskMapper.updateExecution(task);
        };

        Trigger trigger = new CronTrigger(task.getCronExpression());

        ScheduledFuture<?> future = taskScheduler.schedule(taskRunnable, trigger);
        scheduledTasks.put(task.getTaskName(), future);
    }

    // 内部类实现 TriggerContext
    private static class TriggerContextImpl implements org.springframework.scheduling.TriggerContext {
        private final Date lastScheduledExecutionTime;

        public TriggerContextImpl(Date lastScheduledExecutionTime) {
            this.lastScheduledExecutionTime = lastScheduledExecutionTime;
        }

        @Override
        public Date lastScheduledExecutionTime() {
            return lastScheduledExecutionTime;
        }

        @Override
        public Date lastActualExecutionTime() {
            return null;
        }

        @Override
        public Date lastCompletionTime() {
            return null;
        }
    }

    private void cancelAllTasks() {
        scheduledTasks.values().forEach(future -> {
            if (future != null) {
                future.cancel(false); // false 表示不中断正在执行的任务
            }
        });
        scheduledTasks.clear();
    }
}

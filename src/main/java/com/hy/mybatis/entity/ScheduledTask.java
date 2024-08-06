package com.hy.mybatis.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ScheduledTask {

    private Long id;// 任务ID

    private String taskName;// 任务名称

    private String cronExpression;// Cron 表达式

    private Integer enabled;// 任务是否启用

    private LocalDateTime lastExecution;// 上次任务执行时间

    private LocalDateTime nextExecution;// 下次预定执行时间

    private LocalDateTime createdAt;   // 记录创建时间

    private LocalDateTime updatedAt;  // 记录最后更新时间

    private String remarks;           // 备注

    private String bvNo;              // 对应的bv号

    private String markNo;            // 对应的评论ID

    private Integer allLog;           // 是否全量查询

    private Integer type;             // 类型

    private String url; //后续获取评论URL

    private Long requestInfoId; //对应请求参数配置

    private String paramsType; //评论请求参数中的type

}

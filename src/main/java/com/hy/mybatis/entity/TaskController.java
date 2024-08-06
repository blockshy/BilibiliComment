package com.hy.mybatis.entity;

import lombok.Data;

@Data
public class TaskController {
    private Integer id;
    private String taskName;
    private Integer taskType;
    private Integer enabled;
    private String taskRemark;
    private String cronExpression;
}

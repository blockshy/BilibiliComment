package com.hy.mybatis.entity;

import lombok.Data;

@Data
public class ScheduledTasksAllLog {

    private Long id;

    private String taskName;

    private Boolean enabled;

    private String remarks;

    private String bvNo;

    private String markNo;

    private Integer allLog;

    private Integer type;

    private String url;

    private Integer requestInfoId;

    private Integer lastCursor;
}

package com.hy.mybatis.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskRequestInfo {

    private Long id;// ID

    private String cookie;// Cookie

    private String acTimeValue;
}

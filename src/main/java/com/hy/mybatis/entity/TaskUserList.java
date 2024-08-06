package com.hy.mybatis.entity;

import lombok.Data;

@Data
public class TaskUserList {

    private Long id;// ID

    private String uid;// UID

    private Integer enabledDynamic; //是否启用动态

    private Integer enabledVideo; //是否启用视频

    private Long requestInfoId; //使用cookieid
}

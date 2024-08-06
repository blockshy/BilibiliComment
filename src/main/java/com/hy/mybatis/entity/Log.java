package com.hy.mybatis.entity;

import lombok.Data;

import java.util.Date;

@Data
public class Log {

    private int id;
    private String mid;
    private String uname;
    private String avatar;
    private Integer currentLevel;
    private String content;
    private Date ctime;
    private Long rpid;
    private String parent;
}

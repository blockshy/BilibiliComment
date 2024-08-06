package com.hy.service;

import com.hy.mybatis.mapper.ScheduledTaskMapper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
public class TestService {

    @Resource
    private ScheduledTaskMapper taskMapper;

    @Async("taskExecutor")
    public void testInset() throws InterruptedException {
        Thread.sleep(10000);
        System.out.println(taskMapper.findAll());
    }
}

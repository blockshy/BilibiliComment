package com.hy.controller;

import com.hy.service.TestService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import javax.annotation.Resource;
@RestController
@RequestMapping("/api/test")
public class TestApiController {

    @Resource
    private TestService testService;

    @RequestMapping(value = "/one", method = {RequestMethod.GET})
    public void one() throws InterruptedException {
        int i = 1000;
        for (int index = 0;index<i;index++){
            testService.testInset();
        }
        System.out.println("111111");
    }
}

package com.hy.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan(basePackages = {"com.hy.mybatis.mapper"})
public class MybatisConfig {
}

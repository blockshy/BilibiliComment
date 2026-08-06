package com.hy;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@MapperScan("com.hy.bilicomment.infrastructure.persistence.mapper")
@ConfigurationPropertiesScan("com.hy.bilicomment.config")
@SpringBootApplication
public class BiliBiliCommentApplication {

    public static void main(String[] args) {
        if (DatabaseMigrationApplication.isMigrationMode(args)) {
            DatabaseMigrationApplication.run(args);
            return;
        }
        SpringApplication.run(BiliBiliCommentApplication.class, args);
    }
}

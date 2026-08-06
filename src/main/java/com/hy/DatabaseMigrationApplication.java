package com.hy;

import java.util.Arrays;
import java.util.stream.Stream;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

final class DatabaseMigrationApplication {

    private DatabaseMigrationApplication() {}

    static boolean isMigrationMode(String[] args) {
        String environmentMode = System.getenv("APP_MODE");
        return "migrate".equalsIgnoreCase(environmentMode)
                || Arrays.stream(args).anyMatch(argument ->
                        "--app.mode=migrate".equalsIgnoreCase(argument));
    }

    static void run(String[] args) {
        SpringApplication application = new SpringApplication(MigrationConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        String[] effectiveArguments = Stream.concat(
                        Arrays.stream(args)
                                .filter(argument -> !argument.toLowerCase()
                                        .startsWith("--spring.flyway.enabled=")),
                        Stream.of("--spring.flyway.enabled=true"))
                .toArray(String[]::new);
        try (ConfigurableApplicationContext ignored = application.run(effectiveArguments)) {
            // Flyway migration runs during context initialization. A clean close
            // makes this mode a deterministic one-shot process.
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        FlywayAutoConfiguration.class
    })
    static class MigrationConfiguration {}
}

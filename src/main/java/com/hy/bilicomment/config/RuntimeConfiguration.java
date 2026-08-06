package com.hy.bilicomment.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RuntimeConfiguration {

    @Bean
    Clock clock() {
        return Clock.system(ZoneOffset.UTC);
    }

    @Bean
    HttpClient httpClient(AppProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getBilibili().getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}

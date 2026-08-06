package com.hy.bilicomment.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class RuntimeConfigurationTests {

    @Test
    void sharedHttpClientNeverFollowsRedirectsThatCouldReceiveCredentials() {
        AppProperties properties = new AppProperties();

        HttpClient httpClient = new RuntimeConfiguration().httpClient(properties);

        assertThat(httpClient.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
    }
}

package com.hy.bilicomment.infrastructure.bilibili;

import static org.assertj.core.api.Assertions.assertThat;

import com.hy.bilicomment.config.AppProperties;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CredentialRequestGateTests {

    @Test
    void sameCredentialCannotExceedItsConfiguredConcurrency() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getBilibili().setMaximumConcurrentRequestsPerCredential(1);
        properties.getBilibili().setMinimumRequestInterval(Duration.ZERO);
        CredentialRequestGate gate = new CredentialRequestGate(properties);
        CountDownLatch acquired = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (CredentialRequestGate.Permit first = gate.acquire(7L)) {
            executor.submit(() -> {
                try (CredentialRequestGate.Permit ignored = gate.acquire(7L)) {
                    acquired.countDown();
                }
            });
            assertThat(acquired.await(100, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            executor.shutdown();
        }

        assertThat(acquired.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void sameCredentialRequestsRespectTheConfiguredMinimumInterval() {
        AppProperties properties = new AppProperties();
        properties.getBilibili().setMaximumConcurrentRequestsPerCredential(2);
        properties.getBilibili().setMinimumRequestInterval(Duration.ofMillis(80));
        CredentialRequestGate gate = new CredentialRequestGate(properties);

        try (CredentialRequestGate.Permit ignored = gate.acquire(7L)) {
            // The first reservation establishes the next allowed request time.
        }
        long started = System.nanoTime();
        try (CredentialRequestGate.Permit ignored = gate.acquire(7L)) {
            // Acquisition itself performs the interval wait.
        }

        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isGreaterThanOrEqualTo(Duration.ofMillis(50));
    }
}

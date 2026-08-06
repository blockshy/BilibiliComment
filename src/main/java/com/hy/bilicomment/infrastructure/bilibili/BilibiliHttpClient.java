package com.hy.bilicomment.infrastructure.bilibili;

import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.error.DomainException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class BilibiliHttpClient {

    private static final Logger log = LoggerFactory.getLogger(BilibiliHttpClient.class);

    private final HttpClient httpClient;
    private final CredentialRequestGate requestGate;
    private final Duration responseTimeout;
    private final int maximumAttempts;
    private final String userAgent;
    private final BilibiliRequestMetrics metrics;

    public BilibiliHttpClient(
            HttpClient httpClient,
            CredentialRequestGate requestGate,
            AppProperties properties,
            BilibiliRequestMetrics metrics) {
        this.httpClient = httpClient;
        this.requestGate = requestGate;
        this.responseTimeout = properties.getBilibili().getResponseTimeout();
        this.maximumAttempts = Math.max(1, properties.getBilibili().getMaximumAttempts());
        this.userAgent = properties.getBilibili().getUserAgent();
        this.metrics = metrics;
    }

    public String get(URI uri, BilibiliCredential credential) {
        long credentialId = credential == null ? 0 : credential.id();
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            try (CredentialRequestGate.Permit ignored = requestGate.acquire(credentialId)) {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                        .GET()
                        .timeout(responseTimeout)
                        .header("Accept", "application/json,text/html;q=0.8")
                        .header("User-Agent", userAgent)
                        .header("Referer", "https://www.bilibili.com/");
                if (credential != null) {
                    request.header("Cookie", credential.cookie());
                }
                long started = System.nanoTime();
                HttpResponse<String> response = httpClient.send(
                        request.build(), HttpResponse.BodyHandlers.ofString());
                long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
                int status = response.statusCode();
                log.debug("Bilibili request path={} status={} elapsedMs={}", uri.getPath(), status, elapsedMillis);
                if (status >= 200 && status < 300) {
                    metrics.success();
                    return response.body();
                }
                if (status == 429) {
                    metrics.rateLimited();
                } else {
                    metrics.failure();
                }
                if (!isRetryable(status) || attempt == maximumAttempts) {
                    String errorCode = status == 429
                            ? "BILIBILI_RATE_LIMITED"
                            : (isRetryable(status)
                                    ? "BILIBILI_UPSTREAM_UNAVAILABLE"
                                    : "BILIBILI_HTTP_ERROR");
                    throw new DomainException(
                            errorCode,
                            "Bilibili 请求失败，HTTP 状态码 " + status);
                }
            } catch (IOException exception) {
                metrics.failure();
                if (attempt == maximumAttempts) {
                    throw new DomainException("BILIBILI_NETWORK_ERROR", "无法连接 Bilibili", exception);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new DomainException("REQUEST_INTERRUPTED", "Bilibili 请求被中断", exception);
            }
            backoff(attempt);
        }
        throw new IllegalStateException("retry loop completed unexpectedly");
    }

    private boolean isRetryable(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
    }

    private void backoff(int attempt) {
        long baseMillis = Math.min(2_000L, 200L << Math.min(attempt - 1, 3));
        long jitterMillis = ThreadLocalRandom.current().nextLong(50, 201);
        LockSupport.parkNanos(Duration.ofMillis(baseMillis + jitterMillis).toNanos());
        if (Thread.currentThread().isInterrupted()) {
            throw new DomainException("REQUEST_INTERRUPTED", "Bilibili 请求被中断");
        }
    }
}

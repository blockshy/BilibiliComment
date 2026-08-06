package com.hy.bilicomment.infrastructure.bilibili;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.error.DomainException;
import java.net.URI;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BilibiliHttpClientTests {

    @Test
    void treatsRedirectAsAnHttpErrorWithoutSendingAnotherCredentialedRequest() throws Exception {
        HttpClient delegate = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(302);
        when(response.body()).thenReturn("");
        when(delegate.send(
                        any(HttpRequest.class),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(response);
        AppProperties properties = new AppProperties();
        properties.getBilibili().setMaximumAttempts(1);
        properties.getBilibili().setMinimumRequestInterval(Duration.ZERO);
        BilibiliHttpClient client = new BilibiliHttpClient(
                delegate,
                new CredentialRequestGate(properties),
                properties,
                new BilibiliRequestMetrics(Clock.fixed(
                        Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC)));
        BilibiliCredential credential = new BilibiliCredential(7L, "SESSDATA=fixture-secret");

        assertThatThrownBy(() -> client.get(
                        URI.create("https://api.bilibili.com/x/web-interface/nav"), credential))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("BILIBILI_HTTP_ERROR"));

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(delegate).send(
                request.capture(),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertThat(request.getValue().headers().firstValue("Cookie"))
                .contains("SESSDATA=fixture-secret");
    }

    @Test
    void retriesATransientServerErrorAndReturnsTheLaterSuccess() throws Exception {
        HttpClient delegate = mock(HttpClient.class);
        HttpResponse<String> unavailable = response(503, "unavailable");
        HttpResponse<String> success = response(200, "{\"ok\":true}");
        when(delegate.send(
                        any(HttpRequest.class),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(unavailable, success);

        assertThat(client(delegate, 2).get(URI.create("https://api.bilibili.com/x/test"), null))
                .isEqualTo("{\"ok\":true}");

        verify(delegate, times(2)).send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @Test
    void reportsExhaustedServerErrorsAsRetryableUpstreamFailure() throws Exception {
        HttpClient delegate = mock(HttpClient.class);
        HttpResponse<String> unavailable = response(503, "unavailable");
        when(delegate.send(
                        any(HttpRequest.class),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(unavailable);

        assertThatThrownBy(() -> client(delegate, 2)
                        .get(URI.create("https://api.bilibili.com/x/test"), null))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("BILIBILI_UPSTREAM_UNAVAILABLE"));

        verify(delegate, times(2)).send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @Test
    void reportsRateLimitOnlyAfterTheFiniteRetryBudget() throws Exception {
        HttpClient delegate = mock(HttpClient.class);
        HttpResponse<String> limited = response(429, "limited");
        when(delegate.send(
                        any(HttpRequest.class),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(limited);

        assertThatThrownBy(() -> client(delegate, 2)
                        .get(URI.create("https://api.bilibili.com/x/test"), null))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("BILIBILI_RATE_LIMITED"));

        verify(delegate, times(2)).send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @Test
    void doesNotRetryANonRetryableClientError() throws Exception {
        HttpClient delegate = mock(HttpClient.class);
        HttpResponse<String> badRequest = response(400, "bad request");
        when(delegate.send(
                        any(HttpRequest.class),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(badRequest);

        assertThatThrownBy(() -> client(delegate, 3)
                        .get(URI.create("https://api.bilibili.com/x/test"), null))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("BILIBILI_HTTP_ERROR"));

        verify(delegate).send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @Test
    void retriesIoFailuresAndReportsNetworkErrorWhenTheyPersist() throws Exception {
        HttpClient delegate = mock(HttpClient.class);
        when(delegate.send(
                        any(HttpRequest.class),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new IOException("fixture timeout"));

        assertThatThrownBy(() -> client(delegate, 2)
                        .get(URI.create("https://api.bilibili.com/x/test"), null))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("BILIBILI_NETWORK_ERROR"));

        verify(delegate, times(2)).send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    private BilibiliHttpClient client(HttpClient delegate, int attempts) {
        AppProperties properties = new AppProperties();
        properties.getBilibili().setMaximumAttempts(attempts);
        properties.getBilibili().setMinimumRequestInterval(Duration.ZERO);
        return new BilibiliHttpClient(
                delegate,
                new CredentialRequestGate(properties),
                properties,
                new BilibiliRequestMetrics(Clock.fixed(
                        Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC)));
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}

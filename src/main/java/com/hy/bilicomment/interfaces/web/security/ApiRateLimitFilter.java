package com.hy.bilicomment.interfaces.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

public final class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_TRACKED_KEYS = 10_000;

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong requests = new AtomicLong();

    public ApiRateLimitFilter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Limit limit = limitFor(request);
        if (limit == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Instant now = clock.instant();
        if ((requests.incrementAndGet() & 255) == 0) {
            windows.entrySet().removeIf(entry -> !entry.getValue().endsAt().isAfter(now));
        }
        String client = request.getUserPrincipal() == null
                ? request.getRemoteAddr()
                : request.getUserPrincipal().getName();
        String key = limit.scope() + ':' + client;
        if (!windows.containsKey(key) && windows.size() >= MAX_TRACKED_KEYS) {
            reject(request, response, 60);
            return;
        }

        AtomicBoolean allowed = new AtomicBoolean();
        Window current = windows.compute(key, (ignored, existing) -> {
            if (existing == null || !existing.endsAt().isAfter(now)) {
                allowed.set(true);
                return new Window(1, now.plus(1, ChronoUnit.MINUTES));
            }
            if (existing.count() >= limit.requestsPerMinute()) {
                return existing;
            }
            allowed.set(true);
            return new Window(existing.count() + 1, existing.endsAt());
        });
        if (!allowed.get()) {
            long retryAfter = Math.max(1, current.endsAt().getEpochSecond() - now.getEpochSecond());
            reject(request, response, retryAfter);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private Limit limitFor(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("POST".equals(method) && "/api/v1/auth/login".equals(path)) {
            return new Limit("login", 10);
        }
        if ("POST".equals(method) && "/api/v1/sources/resolve".equals(path)) {
            return new Limit("source-resolve", 30);
        }
        if ("POST".equals(method) && "/api/v1/tasks".equals(path)) {
            return new Limit("task-create", 20);
        }
        if ("POST".equals(method) && path.matches("^/api/v1/tasks/[1-9][0-9]*/run$")) {
            return new Limit("task-run", 30);
        }
        if ("POST".equals(method)
                && path.matches("^/api/v1/credentials/[1-9][0-9]*/validate$")) {
            return new Limit("credential-validate", 10);
        }
        if ("POST".equals(method)
                && path.matches("^/api/v1/tasks/[1-9][0-9]*/comments/search$")) {
            return new Limit("comment-search", 120);
        }
        if ("POST".equals(method)
                && path.matches("^/api/v1/tasks/[1-9][0-9]*/comment-exports$")) {
            return new Limit("comment-export-create", 10);
        }
        if ("GET".equals(method)
                && path.matches("^/api/v1/comment-exports/[1-9][0-9]*/download$")) {
            return new Limit("comment-export-download", 30);
        }
        return null;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, long retryAfter)
            throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("status", 429);
        problem.put("code", "RATE_LIMITED");
        problem.put("detail", "请求过于频繁，请稍后重试");
        Object traceId = request.getAttribute(TraceIdFilter.ATTRIBUTE);
        if (traceId != null) {
            problem.put("traceId", traceId.toString());
        }
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private record Limit(String scope, int requestsPerMinute) {}

    private record Window(int count, Instant endsAt) {}
}

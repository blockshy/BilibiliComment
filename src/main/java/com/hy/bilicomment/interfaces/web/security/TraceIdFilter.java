package com.hy.bilicomment.interfaces.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requested = request.getHeader("X-Request-ID");
        String traceId = requested != null && SAFE_TRACE_ID.matcher(requested).matches()
                ? requested
                : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, traceId);
        response.setHeader("X-Trace-ID", traceId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("traceId", traceId)) {
            filterChain.doFilter(request, response);
        }
    }
}

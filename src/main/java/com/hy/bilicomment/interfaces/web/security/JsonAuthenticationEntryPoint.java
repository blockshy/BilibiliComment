package com.hy.bilicomment.interfaces.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("title", "Unauthorized");
        problem.put("status", 401);
        problem.put("code", "AUTHENTICATION_REQUIRED");
        problem.put("detail", "请先登录");
        Object traceId = request.getAttribute(TraceIdFilter.ATTRIBUTE);
        if (traceId != null) {
            problem.put("traceId", traceId.toString());
        }
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}

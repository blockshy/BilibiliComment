package com.hy.bilicomment.interfaces.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JsonAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        boolean csrf = accessDeniedException instanceof CsrfException;
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("title", "Forbidden");
        problem.put("status", 403);
        problem.put("code", csrf ? "CSRF_REJECTED" : "ACCESS_DENIED");
        problem.put("detail", csrf ? "安全令牌已失效，请刷新页面后重试" : "请求被拒绝");
        Object traceId = request.getAttribute(TraceIdFilter.ATTRIBUTE);
        if (traceId != null) {
            problem.put("traceId", traceId.toString());
        }
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}

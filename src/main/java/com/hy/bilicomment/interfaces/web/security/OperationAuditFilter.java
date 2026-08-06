package com.hy.bilicomment.interfaces.web.security;

import com.hy.bilicomment.application.audit.OperationAuditService;
import com.hy.bilicomment.application.audit.OperationAuditService.AuditEntry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class OperationAuditFilter extends OncePerRequestFilter {

    private static final Pattern TASK_ACTION =
            Pattern.compile("^/api/v1/tasks/([1-9][0-9]*)/(pause|resume|run)$");
    private static final Pattern CREDENTIAL_ACTION =
            Pattern.compile("^/api/v1/credentials/([1-9][0-9]*)/(secret|validate)$");
    private static final Pattern COMMENT_EXPORT_CREATE =
            Pattern.compile("^/api/v1/tasks/([1-9][0-9]*)/comment-exports$");
    private static final Pattern COMMENT_EXPORT_ACTION =
            Pattern.compile("^/api/v1/comment-exports/([1-9][0-9]*)(/download)?$");

    private final OperationAuditService auditService;

    public OperationAuditFilter(OperationAuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        AuditAction action = actionFor(request);
        if (action == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String actorBefore = principalName(request.getUserPrincipal());
        try {
            filterChain.doFilter(request, response);
        } finally {
            String actorAfter = principalName(SecurityContextHolder.getContext().getAuthentication());
            auditService.record(new AuditEntry(
                    actorAfter == null ? (actorBefore == null ? "anonymous" : actorBefore) : actorAfter,
                    action.action(),
                    action.targetType(),
                    action.targetId(),
                    outcome(response.getStatus()),
                    traceId(request),
                    request.getRemoteAddr()));
        }
    }

    private AuditAction actionFor(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("POST".equals(method) && "/api/v1/auth/login".equals(path)) {
            return new AuditAction("AUTH_LOGIN", "SESSION", null);
        }
        if ("POST".equals(method) && "/api/v1/auth/logout".equals(path)) {
            return new AuditAction("AUTH_LOGOUT", "SESSION", null);
        }
        if ("POST".equals(method) && "/api/v1/sources/resolve".equals(path)) {
            return new AuditAction("SOURCE_RESOLVE", "SOURCE", null);
        }
        if ("POST".equals(method) && "/api/v1/tasks".equals(path)) {
            return new AuditAction("TASK_CREATE", "TASK", null);
        }
        Matcher task = TASK_ACTION.matcher(path);
        if ("POST".equals(method) && task.matches()) {
            return new AuditAction("TASK_" + task.group(2).toUpperCase(), "TASK", task.group(1));
        }
        Matcher credential = CREDENTIAL_ACTION.matcher(path);
        if (credential.matches()
                && (("secret".equals(credential.group(2)) && "PUT".equals(method))
                        || ("validate".equals(credential.group(2)) && "POST".equals(method)))) {
            return new AuditAction(
                    "CREDENTIAL_" + credential.group(2).toUpperCase(),
                    "CREDENTIAL",
                    credential.group(1));
        }
        Matcher exportCreate = COMMENT_EXPORT_CREATE.matcher(path);
        if ("POST".equals(method) && exportCreate.matches()) {
            return new AuditAction("COMMENT_EXPORT_CREATE", "TASK", exportCreate.group(1));
        }
        Matcher export = COMMENT_EXPORT_ACTION.matcher(path);
        if (export.matches()) {
            if ("DELETE".equals(method) && export.group(2) == null) {
                return new AuditAction("COMMENT_EXPORT_CANCEL", "COMMENT_EXPORT", export.group(1));
            }
            if ("GET".equals(method) && export.group(2) != null) {
                return new AuditAction("COMMENT_EXPORT_DOWNLOAD", "COMMENT_EXPORT", export.group(1));
            }
        }
        return null;
    }

    private String outcome(int status) {
        if (status >= 500) {
            return "FAILED";
        }
        return status >= 400 ? "REJECTED" : "SUCCESS";
    }

    private String principalName(Principal principal) {
        if (principal == null) {
            return null;
        }
        if (principal instanceof Authentication authentication && !authentication.isAuthenticated()) {
            return null;
        }
        return principal.getName();
    }

    private String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(TraceIdFilter.ATTRIBUTE);
        return value == null ? null : value.toString();
    }

    private record AuditAction(String action, String targetType, String targetId) {}
}

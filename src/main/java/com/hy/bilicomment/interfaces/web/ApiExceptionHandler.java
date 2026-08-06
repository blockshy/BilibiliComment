package com.hy.bilicomment.interfaces.web;

import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.error.TaskConflictException;
import com.hy.bilicomment.interfaces.web.security.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(TaskConflictException.class)
    ProblemDetail taskConflict(TaskConflictException exception, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, exception.getCode(), exception.getMessage(), request);
        problem.setProperty("existingTaskId", Long.toString(exception.getExistingTaskId()));
        return problem;
    }

    @ExceptionHandler(DomainException.class)
    ProblemDetail domain(DomainException exception, HttpServletRequest request) {
        HttpStatus status = statusFor(exception.getCode());
        return problem(status, exception.getCode(), exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "VALIDATION_FAILED",
                "请求参数验证失败",
                request);
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        problem.setProperty("fieldErrors", fields);
        return problem;
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MissingRequestHeaderException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class
    })
    ProblemDetail malformedRequest(Exception exception, HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "REQUEST_INVALID",
                "请求格式或参数无效",
                request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail authentication(AuthenticationException exception, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "用户名或密码错误", request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail responseStatus(ResponseStatusException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return problem(status, status == HttpStatus.UNAUTHORIZED
                ? "AUTHENTICATION_REQUIRED"
                : "REQUEST_REJECTED", exception.getReason(), request);
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail database(DataAccessException exception, HttpServletRequest request) {
        log.error("Database operation failed", exception);
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "DATABASE_UNAVAILABLE",
                "数据库暂时不可用，请稍后重试",
                request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled API error", exception);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "服务发生未预期错误",
                request);
    }

    private ProblemDetail problem(
            HttpStatus status,
            String code,
            String detail,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status,
                detail == null || detail.isBlank() ? status.getReasonPhrase() : detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        Object traceId = request.getAttribute(TraceIdFilter.ATTRIBUTE);
        if (traceId != null) {
            problem.setProperty("traceId", traceId.toString());
        }
        return problem;
    }

    private HttpStatus statusFor(String code) {
        return switch (code) {
            case "TASK_NOT_FOUND", "EXECUTION_NOT_FOUND", "CREDENTIAL_NOT_FOUND",
                    "COMMENT_EXPORT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "TASK_ALREADY_EXISTS", "TASK_ALREADY_RUNNING", "TASK_CONCURRENT_MODIFICATION",
                    "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_IN_PROGRESS",
                    "COMMENT_EXPORT_NOT_READY" -> HttpStatus.CONFLICT;
            case "COMMENT_EXPORT_EXPIRED", "EXPORT_FILE_NOT_FOUND" -> HttpStatus.GONE;
            case "BILIBILI_RATE_LIMITED", "COMMENT_EXPORT_QUEUE_FULL",
                    "COMMENT_EXPORT_DOWNLOAD_BUSY" -> HttpStatus.TOO_MANY_REQUESTS;
            case "DATABASE_UNAVAILABLE", "BILIBILI_NETWORK_ERROR",
                    "BILIBILI_UPSTREAM_UNAVAILABLE", "COMMENT_EXPORT_DISABLED",
                    "COMMENT_EXPORT_FILE_UNAVAILABLE", "EXPORT_FILE_TAMPERED",
                    "EXPORT_FILE_INVALID" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "BILIBILI_HTTP_ERROR", "BILIBILI_API_ERROR", "BILIBILI_RESPONSE_INVALID",
                    "BILIBILI_DATA_MISSING" -> HttpStatus.BAD_GATEWAY;
            case "SOURCE_HOST_NOT_ALLOWED" -> HttpStatus.FORBIDDEN;
            case "AUTHENTICATION_REQUIRED", "LOGIN_FAILED" -> HttpStatus.UNAUTHORIZED;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }
}

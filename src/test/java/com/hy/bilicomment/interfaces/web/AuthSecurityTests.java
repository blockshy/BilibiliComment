package com.hy.bilicomment.interfaces.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.config.SecurityConfiguration;
import com.hy.bilicomment.application.audit.OperationAuditService;
import com.hy.bilicomment.interfaces.web.security.JsonAccessDeniedHandler;
import com.hy.bilicomment.interfaces.web.security.JsonAuthenticationEntryPoint;
import com.hy.bilicomment.infrastructure.persistence.mapper.CredentialMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.CommentExportJobMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.EventMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.IdempotencyMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.OperationAuditMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskDiscoveryRelationMapper;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(AuthController.class)
@TestPropertySource(properties = {
    "app.admin.username=fixture-admin",
    "app.admin.password-hash=$2b$04$3Fes0J/H6vFND/6xJ0w7LOePze2Y2lQSw8V.CTI1T6/u01.aVhVu."
})
@Import({
    SecurityConfiguration.class,
    JsonAuthenticationEntryPoint.class,
    JsonAccessDeniedHandler.class,
    ApiExceptionHandler.class,
    AuthSecurityTests.FixtureConfiguration.class
})
class AuthSecurityTests {

    private static final String USERNAME = "fixture-admin";
    private static final String PASSWORD = "fixture-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FilterChainProxy securityFilterChainProxy;

    @MockitoBean
    private OperationAuditService operationAuditService;

    @MockitoBean
    private CredentialMapper credentialMapper;

    @MockitoBean
    private CommentExportJobMapper commentExportJobMapper;

    @MockitoBean
    private EventMapper eventMapper;

    @MockitoBean
    private ExecutionMapper executionMapper;

    @MockitoBean
    private IdempotencyMapper idempotencyMapper;

    @MockitoBean
    private OperationAuditMapper operationAuditMapper;

    @MockitoBean
    private TaskMapper taskMapper;

    @MockitoBean
    private TaskDiscoveryRelationMapper taskDiscoveryRelationMapper;

    @Test
    void unauthenticatedApiUsesJsonProblemDetails() throws Exception {
        mockMvc.perform(get("/api/v1/tasks")
                        .header("X-Request-ID", "fixture-unauthenticated-trace"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Trace-ID", "fixture-unauthenticated-trace"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.traceId").value("fixture-unauthenticated-trace"))
                .andExpect(jsonPath("$.detail").value("请先登录"));
    }

    @Test
    void safeBootstrapRequestPublishesTheCsrfCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().path("XSRF-TOKEN", "/"))
                .andReturn();

        assertThat(result.getResponse().getCookie("XSRF-TOKEN").getAttribute("SameSite"))
                .isEqualTo("Strict");
    }

    @Test
    void authEndpointUsesTheCookieCsrfRepository() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        SecurityFilterChain chain = securityFilterChainProxy.getFilterChains().stream()
                .filter(candidate -> candidate.matches(request))
                .findFirst()
                .orElseThrow();
        CsrfFilter csrfFilter = chain.getFilters().stream()
                .filter(CsrfFilter.class::isInstance)
                .map(CsrfFilter.class::cast)
                .findFirst()
                .orElseThrow();

        assertThat(ReflectionTestUtils.getField(csrfFilter, "tokenRepository"))
                .isInstanceOf(CookieCsrfTokenRepository.class);
    }

    @Test
    void loginWithoutCsrfIsRejectedAsJson() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Request-ID", "fixture-csrf-rejection-trace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Trace-ID", "fixture-csrf-rejection-trace"))
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"))
                .andExpect(jsonPath("$.traceId").value("fixture-csrf-rejection-trace"));
    }

    @Test
    void validLoginCreatesAnAuthenticatedServerSession() throws Exception {
        CsrfCookie csrf = bootstrapCsrf();
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .with(withCsrf(csrf))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME))
                .andExpect(jsonPath("$.displayName").value("管理员"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context).isNotNull();
        assertThat(context.getAuthentication().isAuthenticated()).isTrue();
        assertThat(context.getAuthentication().getName()).isEqualTo(USERNAME);
    }

    @Test
    void successfulLoginRotatesAnExistingSessionId() throws Exception {
        CsrfCookie csrf = bootstrapCsrf();
        MockHttpSession existing = new MockHttpSession();
        String originalSessionId = existing.getId();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .session(existing)
                        .with(withCsrf(csrf))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession authenticated = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(authenticated).isNotNull();
        assertThat(authenticated.getId()).isNotEqualTo(originalSessionId);
    }

    @Test
    void invalidPasswordReturnsGenericFailureWithoutEchoingCredentials() throws Exception {
        CsrfCookie csrf = bootstrapCsrf();
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(withCsrf(csrf))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("definitely-wrong")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"))
                .andExpect(jsonPath("$.detail").value("用户名或密码错误"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("definitely-wrong"))));
    }

    @Test
    void authenticatedStateChangeStillRequiresCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(user(USERNAME).roles("ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));

        CsrfCookie csrf = bootstrapCsrf();
        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(user(USERNAME).roles("ADMIN"))
                        .with(withCsrf(csrf)))
                .andExpect(status().isNoContent());
    }

    @Test
    void throttlesRepeatedLoginAttemptsWithoutEchoingSecrets() throws Exception {
        CsrfCookie csrf = bootstrapCsrf();
        for (int attempt = 0; attempt < 10; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(withCsrf(csrf))
                            .with(request -> {
                                request.setRemoteAddr("192.0.2.50");
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson("wrong-rate-limit-fixture")))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(withCsrf(csrf))
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.50");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("wrong-rate-limit-fixture")))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Retry-After", "60"));
    }

    private String loginJson(String password) {
        return """
                {"username":"%s","password":"%s"}
                """.formatted(USERNAME, password);
    }

    private CsrfCookie bootstrapCsrf() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andReturn();
        Cookie csrfCookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        return new CsrfCookie(csrfCookie);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor withCsrf(
            CsrfCookie csrf) {
        return request -> {
            request.setCookies(csrf.cookie());
            request.addHeader("X-XSRF-TOKEN", csrf.cookie().getValue());
            return request;
        };
    }

    private record CsrfCookie(Cookie cookie) {}

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableConfigurationProperties(AppProperties.class)
    static class FixtureConfiguration {

        @Bean
        Clock fixtureClock() {
            return Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}

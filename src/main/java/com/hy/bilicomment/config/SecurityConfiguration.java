package com.hy.bilicomment.config;

import com.hy.bilicomment.application.audit.OperationAuditService;
import com.hy.bilicomment.interfaces.web.security.ApiRateLimitFilter;
import com.hy.bilicomment.interfaces.web.security.JsonAccessDeniedHandler;
import com.hy.bilicomment.interfaces.web.security.JsonAuthenticationEntryPoint;
import com.hy.bilicomment.interfaces.web.security.OperationAuditFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(AppProperties properties) {
        String username = properties.getAdmin().getUsername();
        String passwordHash = properties.getAdmin().getPasswordHash();
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("ADMIN_USERNAME must be configured");
        }
        if (passwordHash == null || !passwordHash.matches("^\\$2[aby]\\$.+")) {
            throw new IllegalStateException("ADMIN_PASSWORD_HASH must contain a BCrypt hash");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(passwordHash)
                .roles("ADMIN")
                .build());
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler,
            ObjectMapper objectMapper,
            Clock clock,
            OperationAuditService auditService) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieName("XSRF-TOKEN");
        csrfRepository.setHeaderName("X-XSRF-TOKEN");
        csrfRepository.setCookiePath("/");
        csrfRepository.setCookieCustomizer(cookie -> cookie.sameSite("Strict"));

        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName("_csrf");

        http
                .addFilterAfter(new OperationAuditFilter(auditService), SecurityContextHolderFilter.class)
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfHandler))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .addFilterAfter(new ApiRateLimitFilter(objectMapper, clock), CsrfCookieFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/me",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/error")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .securityContext(context -> context.requireExplicitSave(true))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());
        return http.build();
    }

    private static final class CsrfCookieFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}

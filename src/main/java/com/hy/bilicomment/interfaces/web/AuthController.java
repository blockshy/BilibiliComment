package com.hy.bilicomment.interfaces.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;

    public AuthController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
    }

    @PostMapping("/login")
    public AdminUser login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        if (servletRequest.getSession(false) != null) {
            servletRequest.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, servletRequest, servletResponse);
        return new AdminUser(authentication.getName(), "管理员");
    }

    @GetMapping("/me")
    public AdminUser me(Authentication authentication, HttpServletRequest request) {
        Object csrf = request.getAttribute(CsrfToken.class.getName());
        if (csrf instanceof CsrfToken csrfToken) {
            csrfToken.getToken();
        }
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return new AdminUser(authentication.getName(), "管理员");
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            HttpServletRequest request,
            HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContext empty = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(empty);
        securityContextRepository.saveContext(empty, request, response);
    }

    public record LoginRequest(
            @NotBlank @Size(max = 128) String username,
            @NotBlank @Size(max = 512) String password) {}

    public record AdminUser(String username, String displayName) {}
}

package com.agentplatform.identity;

import com.agentplatform.common.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
public class MustChangePasswordFilter extends OncePerRequestFilter {
    private static final Set<String> ALLOWED = Set.of(
            "/api/v1/auth/csrf", "/api/v1/auth/me", "/api/v1/auth/change-password", "/api/v1/auth/logout"
    );
    private final ObjectMapper mapper;

    public MustChangePasswordFilter(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof PlatformPrincipal principal
                && principal.mustChangePassword() && !ALLOWED.contains(request.getRequestURI())) {
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ApiError.of("PASSWORD_CHANGE_REQUIRED", "请先修改临时密码"));
            return;
        }
        chain.doFilter(request, response);
    }
}

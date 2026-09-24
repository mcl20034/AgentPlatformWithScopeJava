package com.agentplatform.identity;

import com.agentplatform.common.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder(SecurityProperties properties) {
        return new BCryptPasswordEncoder(properties.bcryptStrength());
    }

    @Bean DaoAuthenticationProvider authenticationProvider(PlatformUserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    @Bean AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, MustChangePasswordFilter mustChangePasswordFilter,
                                                   ObjectMapper objectMapper) throws Exception {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieName("XSRF-TOKEN");
        csrf.setHeaderName("X-XSRF-TOKEN");
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return http
                .csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(handler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/login", "/actuator/health").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeError(response, objectMapper, 401, "UNAUTHENTICATED", "请先登录"))
                        .accessDeniedHandler((request, response, exception) -> writeError(response, objectMapper, 403, "FORBIDDEN", "你没有权限执行此操作")))
                .addFilterAfter(mustChangePasswordFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private static void writeError(jakarta.servlet.http.HttpServletResponse response, ObjectMapper mapper,
                                   int status, String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiError.of(code, message));
    }
}

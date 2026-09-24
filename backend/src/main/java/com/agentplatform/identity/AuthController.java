package com.agentplatform.identity;

import com.agentplatform.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.web.csrf.CsrfToken;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContexts;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessions;
    private final AuditService audit;
    private final SecurityProperties security;

    public AuthController(AuthenticationManager authenticationManager, SecurityContextRepository securityContexts,
                          UserRepository users, PasswordEncoder passwordEncoder, SessionService sessions, AuditService audit,SecurityProperties security) {
        this.authenticationManager = authenticationManager;
        this.securityContexts = securityContexts;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.audit = audit;
        this.security=security;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/login")
    public UserView login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        String username=UserRepository.normalize(body.username());
        AppUser candidate=users.findByUsername(username).orElse(null);
        if(candidate!=null&&users.isLocked(username))throw new BusinessException(423,"ACCOUNT_LOCKED","登录失败次数过多，请稍后重试或联系管理员解锁");
        Authentication authentication;
        try{authentication=authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, body.password()));}
        catch(AuthenticationException exception){if(candidate!=null){users.recordLoginFailure(candidate.id(),security.effectiveMaxFailedLogins(),security.effectiveLockMinutes());audit.record(candidate.id(),"LOGIN","USER",candidate.id(),"FAILED");}throw exception;}
        request.getSession(true);
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContexts.saveContext(context, request, response);
        PlatformPrincipal principal = (PlatformPrincipal) authentication.getPrincipal();
        users.recordLogin(principal.id());
        audit.record(principal.id(), "LOGIN", "USER", principal.id(), "SUCCESS");
        return UserView.from(users.findById(principal.id()).orElseThrow());
    }

    @GetMapping("/me")
    public UserView me(Authentication authentication) {
        PlatformPrincipal principal = principal(authentication);
        AppUser user = users.findById(principal.id()).orElseThrow(() -> new BusinessException(401, "UNAUTHENTICATED", "登录状态已失效"));
        if (!user.enabled()) throw new BusinessException(401, "ACCOUNT_DISABLED", "账号已停用");
        return UserView.from(user);
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof PlatformPrincipal principal) {
            audit.record(principal.id(), "LOGOUT", "USER", principal.id(), "SUCCESS");
        }
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }

    @PostMapping("/change-password")
    @Transactional
    public UserView changePassword(@Valid @RequestBody ChangePasswordRequest body, Authentication authentication,
                                   HttpServletRequest request, HttpServletResponse response) {
        PlatformPrincipal principal = principal(authentication);
        AppUser user = users.findById(principal.id()).orElseThrow();
        if (!passwordEncoder.matches(body.currentPassword(), user.passwordHash())) {
            throw new BusinessException(422, "CURRENT_PASSWORD_INVALID", "当前密码不正确");
        }
        if (passwordEncoder.matches(body.newPassword(), user.passwordHash())) {
            throw new BusinessException(422, "PASSWORD_REUSED", "新密码不能与当前密码相同");
        }
        PasswordPolicy.validate(body.newPassword());
        users.changePassword(user.id(), passwordEncoder.encode(body.newPassword()), false);
        AppUser updated = users.findById(user.id()).orElseThrow();
        PlatformPrincipal updatedPrincipal = PlatformPrincipal.from(updated);
        Authentication updatedAuth = UsernamePasswordAuthenticationToken.authenticated(
                updatedPrincipal, null, updatedPrincipal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(updatedAuth);
        SecurityContextHolder.setContext(context);
        securityContexts.saveContext(context, request, response);
        sessions.invalidateUser(updated.username(), request.getSession().getId());
        audit.record(updated.id(), "CHANGE_PASSWORD", "USER", updated.id(), "SUCCESS");
        return UserView.from(updated);
    }

    private static PlatformPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof PlatformPrincipal principal)) {
            throw new BusinessException(401, "UNAUTHENTICATED", "请先登录");
        }
        return principal;
    }

    public record LoginRequest(@NotBlank(message = "请输入用户名") String username,
                               @NotBlank(message = "请输入密码") String password) {}
    public record ChangePasswordRequest(@NotBlank(message = "请输入当前密码") String currentPassword,
                                        @NotBlank(message = "请输入新密码") String newPassword) {}
    public record UserView(String id, String username, String displayName, Role role, boolean enabled,
                           boolean mustChangePassword) {
        static UserView from(AppUser user) {
            return new UserView(user.id().toString(), user.username(), user.displayName(), user.role(),
                    user.enabled(), user.mustChangePassword());
        }
    }
}

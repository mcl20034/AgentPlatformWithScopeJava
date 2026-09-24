package com.agentplatform.identity;

import com.agentplatform.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final TemporaryPasswordGenerator passwords;
    private final SessionService sessions;
    private final AuditService audit;

    public AdminUserController(UserRepository users, PasswordEncoder encoder, TemporaryPasswordGenerator passwords,
                               SessionService sessions, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.passwords = passwords;
        this.sessions = sessions;
        this.audit = audit;
    }

    @GetMapping
    public List<UserSummary> list() {
        return users.findAll().stream().map(UserSummary::from).toList();
    }

    @PostMapping
    @Transactional
    public CreatedUser create(@Valid @RequestBody CreateUserRequest body, Authentication authentication) {
        String username = UserRepository.normalize(body.username());
        if (users.existsByUsername(username)) throw new BusinessException(409, "USERNAME_EXISTS", "用户名已存在");
        String temporaryPassword = passwords.generate();
        AppUser created = users.create(username, body.displayName(), encoder.encode(temporaryPassword), body.role(), true);
        audit.record(actor(authentication).id(), "CREATE_USER", "USER", created.id(), "SUCCESS");
        return new CreatedUser(UserSummary.from(created), temporaryPassword);
    }

    @PatchMapping("/{id}")
    @Transactional
    public UserSummary updateEnabled(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest body,
                                     Authentication authentication) {
        PlatformPrincipal actor = actor(authentication);
        AppUser target = users.findById(id).orElseThrow(() -> new BusinessException(404, "USER_NOT_FOUND", "账号不存在"));
        if (actor.id().equals(id) && !body.enabled()) {
            throw new BusinessException(422, "CANNOT_DISABLE_SELF", "不能停用当前登录账号");
        }
        if (!body.enabled() && target.role() == Role.ADMIN && users.countEnabledAdmins() <= 1) {
            throw new BusinessException(422, "LAST_ADMIN", "不能停用最后一个可用管理员");
        }
        users.setEnabled(id, body.enabled());
        if (!body.enabled()) sessions.invalidateUser(target.username(), null);
        audit.record(actor.id(), body.enabled() ? "ENABLE_USER" : "DISABLE_USER", "USER", id, "SUCCESS");
        return UserSummary.from(users.findById(id).orElseThrow());
    }

    @PostMapping("/{id}/reset-password")
    @Transactional
    public ResetPasswordResult resetPassword(@PathVariable UUID id, Authentication authentication) {
        AppUser target = users.findById(id).orElseThrow(() -> new BusinessException(404, "USER_NOT_FOUND", "账号不存在"));
        String temporaryPassword = passwords.generate();
        users.changePassword(id, encoder.encode(temporaryPassword), true);
        sessions.invalidateUser(target.username(), null);
        audit.record(actor(authentication).id(), "RESET_PASSWORD", "USER", id, "SUCCESS");
        return new ResetPasswordResult(temporaryPassword);
    }

    @PostMapping("/{id}/unlock") public UserSummary unlock(@PathVariable UUID id,Authentication authentication){users.findById(id).orElseThrow(()->new BusinessException(404,"USER_NOT_FOUND","账号不存在"));users.unlock(id);audit.record(actor(authentication).id(),"UNLOCK_USER","USER",id,"SUCCESS");return UserSummary.from(users.findById(id).orElseThrow());}
    @PostMapping("/{id}/force-logout") public void forceLogout(@PathVariable UUID id,Authentication authentication){AppUser target=users.findById(id).orElseThrow(()->new BusinessException(404,"USER_NOT_FOUND","账号不存在"));sessions.invalidateUser(target.username(),null);audit.record(actor(authentication).id(),"FORCE_LOGOUT","USER",id,"SUCCESS");}

    private static PlatformPrincipal actor(Authentication authentication) {
        return (PlatformPrincipal) authentication.getPrincipal();
    }

    public record CreateUserRequest(
            @NotBlank(message = "请输入用户名")
            @Size(min = 3, max = 64, message = "用户名长度应为 3～64 位")
            @Pattern(regexp = "[A-Za-z0-9._-]+", message = "用户名只能包含字母、数字、点、下划线和连字符") String username,
            @NotBlank(message = "请输入显示名称") @Size(max = 100, message = "显示名称不能超过 100 个字符") String displayName,
            @NotNull(message = "请选择角色") Role role) {}
    public record UpdateUserRequest(boolean enabled) {}
    public record CreatedUser(UserSummary user, String temporaryPassword) {}
    public record ResetPasswordResult(String temporaryPassword) {}
    public record UserSummary(String id, String username, String displayName, Role role, boolean enabled,
                              boolean mustChangePassword,int failedLoginCount,Instant lockedUntil, Instant lastLoginAt, Instant createdAt) {
        static UserSummary from(AppUser user) {
            return new UserSummary(user.id().toString(), user.username(), user.displayName(), user.role(), user.enabled(),
                    user.mustChangePassword(),user.failedLoginCount(),user.lockedUntil(), user.lastLoginAt(), user.createdAt());
        }
    }
}

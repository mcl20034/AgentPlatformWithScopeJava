package com.agentplatform.identity;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public record PlatformPrincipal(
        UUID id, String username, String displayName, String password, Role role,
        boolean enabled, long authVersion, boolean mustChangePassword
) implements UserDetails {
    @Serial private static final long serialVersionUID = 1L;

    public static PlatformPrincipal from(AppUser user) {
        return new PlatformPrincipal(user.id(), user.username(), user.displayName(), user.passwordHash(), user.role(),
                user.enabled(), user.authVersion(), user.mustChangePassword());
    }

    @Override public Collection<SimpleGrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return password; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return enabled; }
}

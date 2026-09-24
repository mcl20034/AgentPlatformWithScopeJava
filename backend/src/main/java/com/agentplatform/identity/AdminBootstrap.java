package com.agentplatform.identity;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final BootstrapProperties properties;

    public AdminBootstrap(UserRepository users, PasswordEncoder encoder, BootstrapProperties properties) {
        this.users = users;
        this.encoder = encoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (users.countEnabledAdmins() > 0) return;
        String password = properties.adminPassword();
        if ((password == null || password.isBlank()) && properties.adminPasswordFile() != null && !properties.adminPasswordFile().isBlank()) {
            password = Files.readString(Path.of(properties.adminPasswordFile())).trim();
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("No enabled administrator exists. Set PLATFORM_ADMIN_PASSWORD or PLATFORM_ADMIN_PASSWORD_FILE for one-time bootstrap.");
        }
        PasswordPolicy.validate(password);
        String username = properties.adminUsername() == null || properties.adminUsername().isBlank() ? "admin" : properties.adminUsername();
        if (users.existsByUsername(username)) {
            throw new IllegalStateException("Bootstrap administrator username exists but no enabled administrator is available.");
        }
        users.create(username, "系统管理员", encoder.encode(password), Role.ADMIN, true);
    }
}

package com.agentplatform.identity;

import java.time.Instant;
import java.util.UUID;

public record AppUser(
        UUID id,
        String username,
        String displayName,
        String passwordHash,
        Role role,
        boolean enabled,
        long authVersion,
        boolean mustChangePassword,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt
) {}

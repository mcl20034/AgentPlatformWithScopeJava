package com.agentplatform.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(UUID actorId, String action, String targetType, UUID targetId, String result) {
        jdbc.update("INSERT INTO audit_log (id, actor_id, action, target_type, target_id, result, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), actorId == null ? null : actorId.toString(), action, targetType,
                targetId == null ? null : targetId.toString(), result, com.agentplatform.common.DatabaseTime.now());
    }
}

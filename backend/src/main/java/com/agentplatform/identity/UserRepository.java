package com.agentplatform.identity;

import com.agentplatform.common.DatabaseTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {
    private static final String COLUMNS = "id, username, display_name, password_hash, role, enabled, auth_version, must_change_password, last_login_at, created_at, updated_at";
    private final JdbcTemplate jdbc;
    private final RowMapper<AppUser> mapper = this::map;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findByUsername(String username) {
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user WHERE username = ?", mapper, normalize(username)).stream().findFirst();
    }

    public Optional<AppUser> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user WHERE id = ?", mapper, id.toString()).stream().findFirst();
    }

    public List<AppUser> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user ORDER BY created_at DESC", mapper);
    }

    public boolean existsByUsername(String username) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username = ?", Integer.class, normalize(username));
        return count != null && count > 0;
    }

    public AppUser create(String username, String displayName, String passwordHash, Role role, boolean mustChangePassword) {
        UUID id = UUID.randomUUID();
        Timestamp now = DatabaseTime.now();
        jdbc.update("""
                INSERT INTO app_user
                (id, username, display_name, password_hash, role, enabled, auth_version, must_change_password, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, TRUE, 1, ?, ?, ?)
                """, id.toString(), normalize(username), displayName.trim(), passwordHash, role.name(), mustChangePassword, now, now);
        return findById(id).orElseThrow();
    }

    public void recordLogin(UUID id) {
        jdbc.update("UPDATE app_user SET last_login_at = ?, updated_at = ? WHERE id = ?", DatabaseTime.now(), DatabaseTime.now(), id.toString());
    }

    public void changePassword(UUID id, String passwordHash, boolean mustChangePassword) {
        jdbc.update("""
                UPDATE app_user SET password_hash = ?, must_change_password = ?, auth_version = auth_version + 1, updated_at = ?
                WHERE id = ?
                """, passwordHash, mustChangePassword, DatabaseTime.now(), id.toString());
    }

    public void setEnabled(UUID id, boolean enabled) {
        jdbc.update("UPDATE app_user SET enabled = ?, auth_version = auth_version + 1, updated_at = ? WHERE id = ?",
                enabled, DatabaseTime.now(), id.toString());
    }

    public long countEnabledAdmins() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE role = 'ADMIN' AND enabled = TRUE", Long.class);
        return count == null ? 0 : count;
    }

    private AppUser map(ResultSet rs, int rowNum) throws SQLException {
        var lastLogin = rs.getTimestamp("last_login_at");
        return new AppUser(
                UUID.fromString(rs.getString("id")), rs.getString("username"), rs.getString("display_name"),
                rs.getString("password_hash"), Role.valueOf(rs.getString("role")), rs.getBoolean("enabled"),
                rs.getLong("auth_version"), rs.getBoolean("must_change_password"),
                lastLogin == null ? null : lastLogin.toInstant(), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    public static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }
}

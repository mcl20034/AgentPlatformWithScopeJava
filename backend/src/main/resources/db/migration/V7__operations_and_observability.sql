ALTER TABLE audit_log ADD COLUMN actor_username VARCHAR(64);
ALTER TABLE audit_log ADD COLUMN request_id VARCHAR(64);
ALTER TABLE audit_log ADD COLUMN ip_address VARCHAR(64);
ALTER TABLE audit_log ADD COLUMN details_json TEXT;
CREATE INDEX idx_audit_log_action ON audit_log (action);
CREATE INDEX idx_audit_log_actor ON audit_log (actor_id);

CREATE TABLE model_call_log (
    id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36),
    provider VARCHAR(32) NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    purpose VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    duration_ms BIGINT NOT NULL,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    total_tokens BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(80),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_model_call_task FOREIGN KEY (task_id) REFERENCES analysis_task(id) ON DELETE SET NULL
);
CREATE INDEX idx_model_call_occurred_at ON model_call_log (occurred_at);
CREATE INDEX idx_model_call_task ON model_call_log (task_id);

ALTER TABLE app_user ADD COLUMN failed_login_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN locked_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE app_user ADD COLUMN last_failed_login_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE user_resource_grant (
    user_id VARCHAR(36) NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    resource_type VARCHAR(30) NOT NULL CHECK (resource_type IN ('MODEL','DATA_SOURCE','KNOWLEDGE_BASE')),
    resource_id VARCHAR(36) NOT NULL,
    granted_by VARCHAR(36) NOT NULL REFERENCES app_user(id),
    granted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, resource_type, resource_id)
);
CREATE INDEX idx_user_resource_grant_resource ON user_resource_grant(resource_type,resource_id);

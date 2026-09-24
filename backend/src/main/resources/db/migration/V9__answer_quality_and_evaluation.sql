ALTER TABLE chat_message ADD COLUMN feedback_rating VARCHAR(20);
ALTER TABLE chat_message ADD COLUMN feedback_comment VARCHAR(500);
ALTER TABLE chat_message ADD COLUMN feedback_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE analysis_task ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE analysis_task ADD COLUMN clarification_requested BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE evaluation_case (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    question VARCHAR(4000) NOT NULL,
    source_type VARCHAR(30) NOT NULL CHECK (source_type IN ('MYSQL','ELASTICSEARCH')),
    expected_terms_json TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(36) NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_evaluation_case_enabled ON evaluation_case(enabled);

ALTER TABLE analysis_task ADD COLUMN evaluation_case_id VARCHAR(36) REFERENCES evaluation_case(id);
CREATE TABLE evaluation_run (
    id VARCHAR(36) PRIMARY KEY,
    case_id VARCHAR(36) NOT NULL REFERENCES evaluation_case(id) ON DELETE CASCADE,
    task_id VARCHAR(36) NOT NULL REFERENCES analysis_task(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,
    score DOUBLE PRECISION,
    matched_terms_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    UNIQUE(task_id)
);

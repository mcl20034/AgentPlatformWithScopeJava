ALTER TABLE chat_message ADD COLUMN query_language VARCHAR(10);
ALTER TABLE chat_message ADD COLUMN evidence_json TEXT;
ALTER TABLE chat_message ADD COLUMN duration_ms BIGINT;
ALTER TABLE analysis_task ADD COLUMN started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE analysis_task ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE analysis_task_event (
    id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL REFERENCES analysis_task(id) ON DELETE CASCADE,
    sequence_no INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    stage VARCHAR(40) NOT NULL,
    message VARCHAR(500) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(task_id, sequence_no)
);

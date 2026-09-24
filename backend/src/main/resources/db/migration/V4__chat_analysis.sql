CREATE TABLE chat_conversation (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
    title VARCHAR(160) NOT NULL,
    model_id VARCHAR(36) NOT NULL REFERENCES model_config(id),
    source_id VARCHAR(36) NOT NULL REFERENCES data_source(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_chat_conversation_user_updated ON chat_conversation(user_id, updated_at);

CREATE TABLE chat_message (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL REFERENCES chat_conversation(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content TEXT NOT NULL,
    sql_text TEXT,
    result_json TEXT,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_chat_message_conversation_created ON chat_message(conversation_id, created_at);

CREATE TABLE analysis_task (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL REFERENCES chat_conversation(id) ON DELETE CASCADE,
    user_message_id VARCHAR(36) NOT NULL REFERENCES chat_message(id),
    assistant_message_id VARCHAR(36) NOT NULL REFERENCES chat_message(id),
    status VARCHAR(20) NOT NULL,
    stage VARCHAR(40) NOT NULL,
    error_code VARCHAR(80),
    error_message VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_analysis_task_conversation_created ON analysis_task(conversation_id, created_at);

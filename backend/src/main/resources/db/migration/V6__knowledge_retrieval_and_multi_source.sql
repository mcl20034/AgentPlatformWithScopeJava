ALTER TABLE kb_generation ADD COLUMN chunk_count INTEGER NOT NULL DEFAULT 0;

CREATE TABLE document_chunk (
 id VARCHAR(36) PRIMARY KEY,
 generation_id VARCHAR(36) NOT NULL REFERENCES kb_generation(id) ON DELETE CASCADE,
 document_version_id VARCHAR(36) NOT NULL REFERENCES document_version(id),
 chunk_index INTEGER NOT NULL,
 page_number INTEGER,
 content TEXT NOT NULL,
 token_count INTEGER NOT NULL,
 embedding_json TEXT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(generation_id,document_version_id,chunk_index)
);
CREATE INDEX idx_document_chunk_generation ON document_chunk(generation_id);

CREATE TABLE chat_conversation_knowledge (
 conversation_id VARCHAR(36) NOT NULL REFERENCES chat_conversation(id) ON DELETE CASCADE,
 kb_id VARCHAR(36) NOT NULL REFERENCES knowledge_base(id),
 PRIMARY KEY(conversation_id,kb_id)
);

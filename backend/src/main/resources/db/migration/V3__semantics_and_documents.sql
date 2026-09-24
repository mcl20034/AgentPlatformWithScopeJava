ALTER TABLE data_source_version ADD COLUMN schema_hash VARCHAR(64);
ALTER TABLE data_source_version ADD COLUMN schema_json TEXT;
ALTER TABLE data_source_version ADD COLUMN allowed_objects_json TEXT;
CREATE TABLE business_metric (
 id VARCHAR(36) PRIMARY KEY, source_version_id VARCHAR(36) NOT NULL REFERENCES data_source_version(id),
 name VARCHAR(100) NOT NULL, description VARCHAR(1000) NOT NULL, expression_json TEXT NOT NULL,
 time_field VARCHAR(255), filters_json TEXT, unit VARCHAR(40), rounding_rule VARCHAR(80),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, UNIQUE(source_version_id,name)
);
CREATE TABLE publication_record (
 id VARCHAR(36) PRIMARY KEY, config_type VARCHAR(30) NOT NULL, config_id VARCHAR(36) NOT NULL,
 version_id VARCHAR(36) NOT NULL, data_sending_confirmed BOOLEAN NOT NULL, confirmed_by VARCHAR(36) NOT NULL,
 published_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE blob_object (
 id VARCHAR(36) PRIMARY KEY, storage_key VARCHAR(500) NOT NULL UNIQUE, sha256 VARCHAR(64) NOT NULL,
 size_bytes BIGINT NOT NULL, media_type VARCHAR(160) NOT NULL, state VARCHAR(20) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE document (
 id VARCHAR(36) PRIMARY KEY, kb_id VARCHAR(36) NOT NULL REFERENCES knowledge_base(id), normalized_name VARCHAR(255) NOT NULL,
 removed_at TIMESTAMP WITH TIME ZONE, created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX uq_document_active_name ON document(kb_id,normalized_name,removed_at);
CREATE TABLE document_version (
 id VARCHAR(36) PRIMARY KEY, document_id VARCHAR(36) NOT NULL REFERENCES document(id), version_no INTEGER NOT NULL,
 content_hash VARCHAR(64) NOT NULL, blob_id VARCHAR(36) NOT NULL REFERENCES blob_object(id), parse_status VARCHAR(30) NOT NULL,
 failure_code VARCHAR(80), extracted_text TEXT, page_count INTEGER, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(document_id,version_no)
);
CREATE TABLE document_job (
 id VARCHAR(36) PRIMARY KEY, kb_id VARCHAR(36) NOT NULL REFERENCES knowledge_base(id), document_version_id VARCHAR(36) NOT NULL REFERENCES document_version(id),
 kind VARCHAR(30) NOT NULL, status VARCHAR(30) NOT NULL, attempt INTEGER NOT NULL DEFAULT 0,
 error_code VARCHAR(80), created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE kb_generation (
 id VARCHAR(36) PRIMARY KEY, kb_id VARCHAR(36) NOT NULL REFERENCES knowledge_base(id), embedding_id VARCHAR(36) NOT NULL REFERENCES embedding_config(id),
 generation_no INTEGER NOT NULL, status VARCHAR(30) NOT NULL, document_count INTEGER NOT NULL DEFAULT 0,
 failure_code VARCHAR(80), created_at TIMESTAMP WITH TIME ZONE NOT NULL, published_at TIMESTAMP WITH TIME ZONE,
 UNIQUE(kb_id,generation_no)
);
ALTER TABLE knowledge_base ADD COLUMN active_generation_id VARCHAR(36);
ALTER TABLE knowledge_base ADD CONSTRAINT fk_kb_active_generation FOREIGN KEY(active_generation_id) REFERENCES kb_generation(id);

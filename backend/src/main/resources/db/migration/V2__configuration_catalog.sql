CREATE TABLE secret_version (
 id VARCHAR(36) PRIMARY KEY, key_id VARCHAR(80) NOT NULL, ciphertext BYTEA NOT NULL, nonce BYTEA NOT NULL,
 secret_kind VARCHAR(40) NOT NULL, created_by VARCHAR(36) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE model_config (
 id VARCHAR(36) PRIMARY KEY, display_name VARCHAR(100) NOT NULL UNIQUE, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 active_version_id VARCHAR(36), revision BIGINT NOT NULL DEFAULT 1, created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE model_version (
 id VARCHAR(36) PRIMARY KEY, model_id VARCHAR(36) NOT NULL REFERENCES model_config(id), version_no INTEGER NOT NULL,
 provider VARCHAR(20) NOT NULL, endpoint VARCHAR(500) NOT NULL, model_name VARCHAR(160) NOT NULL,
 secret_version_id VARCHAR(36) REFERENCES secret_version(id), timeout_ms INTEGER NOT NULL, test_status VARCHAR(20) NOT NULL DEFAULT 'UNTESTED',
 created_by VARCHAR(36) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, UNIQUE(model_id, version_no)
);
ALTER TABLE model_config ADD CONSTRAINT fk_model_active_version FOREIGN KEY(active_version_id) REFERENCES model_version(id);
CREATE TABLE embedding_config (
 id VARCHAR(36) PRIMARY KEY, display_name VARCHAR(100) NOT NULL UNIQUE, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 active_version_id VARCHAR(36), revision BIGINT NOT NULL DEFAULT 1, created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE embedding_version (
 id VARCHAR(36) PRIMARY KEY, embedding_id VARCHAR(36) NOT NULL REFERENCES embedding_config(id), version_no INTEGER NOT NULL,
 provider VARCHAR(20) NOT NULL, endpoint VARCHAR(500) NOT NULL, model_name VARCHAR(160) NOT NULL,
 secret_version_id VARCHAR(36) REFERENCES secret_version(id), timeout_ms INTEGER NOT NULL, dimensions INTEGER,
 test_status VARCHAR(20) NOT NULL DEFAULT 'UNTESTED', created_by VARCHAR(36) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(embedding_id, version_no)
);
ALTER TABLE embedding_config ADD CONSTRAINT fk_embedding_active_version FOREIGN KEY(active_version_id) REFERENCES embedding_version(id);
CREATE TABLE data_source (
 id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL UNIQUE, type VARCHAR(30) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE, active_version_id VARCHAR(36), revision BIGINT NOT NULL DEFAULT 1,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE data_source_version (
 id VARCHAR(36) PRIMARY KEY, source_id VARCHAR(36) NOT NULL REFERENCES data_source(id), version_no INTEGER NOT NULL,
 endpoint VARCHAR(500) NOT NULL, database_name VARCHAR(160), index_name VARCHAR(255), username VARCHAR(160),
 secret_version_id VARCHAR(36) REFERENCES secret_version(id), timezone VARCHAR(80) NOT NULL,
 purpose VARCHAR(1000) NOT NULL, test_status VARCHAR(20) NOT NULL DEFAULT 'UNTESTED', read_only_verified BOOLEAN NOT NULL DEFAULT FALSE,
 created_by VARCHAR(36) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, UNIQUE(source_id, version_no)
);
ALTER TABLE data_source ADD CONSTRAINT fk_source_active_version FOREIGN KEY(active_version_id) REFERENCES data_source_version(id);
CREATE TABLE knowledge_base (
 id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL UNIQUE, description VARCHAR(1000) NOT NULL,
 embedding_id VARCHAR(36) REFERENCES embedding_config(id), enabled BOOLEAN NOT NULL DEFAULT FALSE,
 revision BIGINT NOT NULL DEFAULT 1, created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

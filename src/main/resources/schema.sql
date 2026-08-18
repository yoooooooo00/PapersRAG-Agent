CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS kb_knowledge_base (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    department_id VARCHAR(50) NOT NULL,
    is_public BOOLEAN NOT NULL DEFAULT FALSE,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_kb_department
    ON kb_knowledge_base (department_id) WHERE is_deleted = FALSE;

CREATE TABLE IF NOT EXISTS kb_permission (
    id BIGSERIAL PRIMARY KEY,
    kb_id BIGINT NOT NULL,
    subject_type VARCHAR(20) NOT NULL,
    subject_id VARCHAR(50) NOT NULL,
    permission VARCHAR(20) NOT NULL,
    granted_by BIGINT NOT NULL,
    granted_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_kb_permission_subject UNIQUE (kb_id, subject_type, subject_id)
);

CREATE INDEX IF NOT EXISTS idx_permission_subject
    ON kb_permission (subject_type, subject_id);

CREATE TABLE IF NOT EXISTS kb_document (
    id BIGSERIAL PRIMARY KEY,
    kb_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_type VARCHAR(20) NOT NULL,
    file_size BIGINT NOT NULL,
    minio_path VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    error_msg TEXT,
    chunk_count INT DEFAULT 0,
    token_count INT DEFAULT 0,
    version INT NOT NULL DEFAULT 1,
    uploaded_by BIGINT NOT NULL,
    uploaded_at TIMESTAMP NOT NULL DEFAULT NOW(),
    indexed_at TIMESTAMP,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_doc_kb_id
    ON kb_document (kb_id) WHERE is_deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_doc_status
    ON kb_document (status) WHERE is_deleted = FALSE;

CREATE TABLE IF NOT EXISTS kb_doc_chunk (
    id BIGSERIAL PRIMARY KEY,
    doc_id BIGINT NOT NULL,
    kb_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    content_tsv TSVECTOR,
    embedding VECTOR(1024) NOT NULL,
    page_num INT,
    section_title VARCHAR(500),
    token_count INT NOT NULL DEFAULT 0,
    doc_version INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chunk_embedding
    ON kb_doc_chunk USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 128);

CREATE INDEX IF NOT EXISTS idx_chunk_content_tsv
    ON kb_doc_chunk USING GIN (content_tsv);

CREATE INDEX IF NOT EXISTS idx_chunk_kb_id ON kb_doc_chunk (kb_id);
CREATE INDEX IF NOT EXISTS idx_chunk_doc_id ON kb_doc_chunk (doc_id);

CREATE OR REPLACE FUNCTION update_chunk_tsv()
RETURNS TRIGGER AS $$
BEGIN
    NEW.content_tsv := to_tsvector('simple', NEW.content);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_chunk_tsv ON kb_doc_chunk;
CREATE TRIGGER trigger_chunk_tsv
    BEFORE INSERT OR UPDATE OF content
    ON kb_doc_chunk
    FOR EACH ROW
    EXECUTE FUNCTION update_chunk_tsv();

CREATE TABLE IF NOT EXISTS kb_index_task (
    id BIGSERIAL PRIMARY KEY,
    doc_id BIGINT NOT NULL,
    task_type VARCHAR(20) NOT NULL DEFAULT 'INDEX',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    max_retry INT NOT NULL DEFAULT 3,
    error_msg TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    started_at TIMESTAMP,
    finished_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_task_status ON kb_index_task (status, created_at);
CREATE INDEX IF NOT EXISTS idx_task_doc_id ON kb_index_task (doc_id);

CREATE TABLE IF NOT EXISTS kb_chat_session (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    kb_ids TEXT NOT NULL,
    title VARCHAR(200),
    message_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_active_at TIMESTAMP NOT NULL DEFAULT NOW(),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_session_user
    ON kb_chat_session (user_id, last_active_at DESC) WHERE is_deleted = FALSE;

CREATE TABLE IF NOT EXISTS kb_chat_message (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    sources JSONB,
    latency_ms INT DEFAULT 0,
    feedback SMALLINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_message_session
    ON kb_chat_message (session_id, created_at);

CREATE TABLE IF NOT EXISTS kb_answer_feedback (
    id BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    feedback SMALLINT NOT NULL,
    comment TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_answer_feedback_message_user UNIQUE (message_id, user_id)
);

CREATE TABLE IF NOT EXISTS kb_eval_dataset (
    id BIGSERIAL PRIMARY KEY,
    kb_id BIGINT NOT NULL,
    question TEXT NOT NULL,
    expected_answer TEXT,
    expected_chunk_ids BIGINT[],
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS kb_eval_result (
    id BIGSERIAL PRIMARY KEY,
    dataset_id BIGINT NOT NULL,
    eval_version VARCHAR(50) NOT NULL,
    hit BOOLEAN NOT NULL,
    rank INT,
    actual_answer TEXT,
    faithfulness FLOAT,
    answer_relevancy FLOAT,
    eval_at TIMESTAMP NOT NULL DEFAULT NOW()
);

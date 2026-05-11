-- ============================================================
-- V2 — File metadata table
-- Tracks uploaded input data and code files so the Manager
-- can look up MinIO paths by the IDs returned to the client.
-- ============================================================

CREATE TABLE file_metadata (
    file_id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         VARCHAR(255) NOT NULL,
    file_type       VARCHAR(16)  NOT NULL,   -- 'DATA' or 'CODE'
    original_name   VARCHAR(512) NOT NULL,
    storage_path    VARCHAR(1024) NOT NULL,  -- MinIO object key
    size_bytes      BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_file_metadata_user_id   ON file_metadata (user_id);
CREATE INDEX idx_file_metadata_file_type ON file_metadata (file_type);

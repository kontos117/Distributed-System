-- ============================================================
-- V1 — Initial schema: jobs + tasks
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";  --provides gen_random_uuid()

-- ----------------------------------------------------------
-- Table: jobs
-- Tracks a full Map-Reduce computation submitted by a user.
-- ----------------------------------------------------------
CREATE TABLE jobs (
    job_id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         VARCHAR(255) NOT NULL,          -- Keycloak subject claim
    status          VARCHAR(32)  NOT NULL DEFAULT 'INITIALIZING',
    code_path       VARCHAR(1024),                  -- MinIO URL of the uploaded .jar
    input_path      VARCHAR(1024),                  -- MinIO prefix for input files
    output_path     VARCHAR(1024),                  -- MinIO prefix for final output
    num_map_tasks   INT          NOT NULL DEFAULT 0,
    num_reduce_tasks INT         NOT NULL DEFAULT 1,
    error_message   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_jobs_user_id ON jobs (user_id);
CREATE INDEX idx_jobs_status  ON jobs (status);

-- ----------------------------------------------------------
-- Table: tasks
-- Tracks individual Map or Reduce sub-tasks.
-- The Manager reads this to recover after a pod crash.
-- ----------------------------------------------------------
CREATE TABLE tasks (
    task_id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID        NOT NULL REFERENCES jobs(job_id) ON DELETE CASCADE,
    task_type       VARCHAR(16) NOT NULL,           -- 'MAP' | 'REDUCE'
    status          VARCHAR(16) NOT NULL DEFAULT 'IDLE',
    worker_pod_id   VARCHAR(255),                   -- Kubernetes pod name
    input_split     VARCHAR(1024),                  -- file path / byte range
    output_location VARCHAR(1024),                  -- MinIO URL written by worker
    retry_count     INT         NOT NULL DEFAULT 0,
    last_heartbeat  TIMESTAMPTZ,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tasks_job_id ON tasks (job_id);
CREATE INDEX idx_tasks_status ON tasks (status);

-- ----------------------------------------------------------
-- Trigger: keep updated_at current on every row update
-- ----------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_jobs_updated_at
    BEFORE UPDATE ON jobs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_tasks_updated_at
    BEFORE UPDATE ON tasks
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

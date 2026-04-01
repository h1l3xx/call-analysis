-- =============================================================================
-- V11: Batches, call types, batch summaries for multi-phase pipeline
-- =============================================================================

-- ── 1. batches table ────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS tenant_dev_clinic.batches (
    id               UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    status           TEXT    NOT NULL DEFAULT 'uploading',
    total_calls      INT     NOT NULL DEFAULT 0,
    processed_calls  INT     NOT NULL DEFAULT 0,
    call_type_stats  JSONB,
    created_at       BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
    finished_at      BIGINT,
    CONSTRAINT chk_batch_status CHECK (status IN (
        'uploading', 'transcribing', 'evaluating', 'summarizing', 'done', 'failed'
    ))
);

-- ── 2. batch_summaries table ────────────────────────────────────────

CREATE TABLE IF NOT EXISTS tenant_dev_clinic.batch_summaries (
    id           UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id     UUID    NOT NULL REFERENCES tenant_dev_clinic.batches(id) ON DELETE CASCADE,
    scope        TEXT    NOT NULL DEFAULT 'all',
    period_type  TEXT    NOT NULL DEFAULT 'batch',
    content      JSONB,
    created_at   BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
    CONSTRAINT chk_summary_scope CHECK (scope IN ('internal', 'external', 'all')),
    CONSTRAINT chk_summary_period CHECK (period_type IN ('batch', 'daily', 'weekly', 'custom'))
);

CREATE INDEX IF NOT EXISTS idx_tenant_dev_clinic_batch_summaries_batch
    ON tenant_dev_clinic.batch_summaries (batch_id);

-- ── 3. Add columns to calls ─────────────────────────────────────────

ALTER TABLE tenant_dev_clinic.calls
    ADD COLUMN IF NOT EXISTS batch_id  UUID REFERENCES tenant_dev_clinic.batches(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS call_type TEXT;

CREATE INDEX IF NOT EXISTS idx_tenant_dev_clinic_calls_batch
    ON tenant_dev_clinic.calls (batch_id) WHERE batch_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_tenant_dev_clinic_calls_type
    ON tenant_dev_clinic.calls (call_type) WHERE call_type IS NOT NULL;

-- ── 4. Add is_default to scripts ────────────────────────────────────

ALTER TABLE tenant_dev_clinic.scripts
    ADD COLUMN IF NOT EXISTS is_default BOOLEAN NOT NULL DEFAULT FALSE;

-- ── 5. Update create_tenant_schema for new tenants ──────────────────

CREATE OR REPLACE FUNCTION public.create_tenant_schema(schema_name TEXT)
RETURNS VOID AS $$
BEGIN
    IF schema_name !~ '^tenant_[a-z0-9_]+$' THEN
        RAISE EXCEPTION 'Invalid schema name: %. Must match tenant_[a-z0-9_]+', schema_name;
    END IF;

    EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I', schema_name);

    -- departments
    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.departments (
            id          UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
            name        TEXT    NOT NULL,
            description TEXT,
            is_active   BOOLEAN NOT NULL DEFAULT TRUE,
            created_at  BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
        )
    $sql$, schema_name);

    -- department_leads
    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.department_leads (
            user_id       UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
            department_id UUID NOT NULL REFERENCES %I.departments(id) ON DELETE CASCADE,
            created_at    BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
            PRIMARY KEY (user_id, department_id)
        )
    $sql$, schema_name, schema_name);

    -- managers
    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.managers (
            id              UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
            user_id         UUID    NOT NULL UNIQUE REFERENCES public.users(id) ON DELETE CASCADE,
            department_id   UUID    REFERENCES %I.departments(id),
            extension       TEXT,
            phone_number    TEXT,
            is_active       BOOLEAN NOT NULL DEFAULT TRUE,
            created_at      BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
            updated_at      BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
        )
    $sql$, schema_name, schema_name);

    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_managers_dept ON %I.managers (department_id)',
        replace(schema_name, '.', '_'), schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_managers_phone ON %I.managers (phone_number) WHERE phone_number IS NOT NULL',
        replace(schema_name, '.', '_'), schema_name);

    -- scripts (with is_default)
    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.scripts (
            id          UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
            name        TEXT    NOT NULL,
            call_type   TEXT    NOT NULL,
            description TEXT,
            is_active   BOOLEAN NOT NULL DEFAULT TRUE,
            is_default  BOOLEAN NOT NULL DEFAULT FALSE,
            created_at  BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
            updated_at  BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
        )
    $sql$, schema_name);

    -- criteria
    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.criteria (
            id           SERIAL  PRIMARY KEY,
            script_id    UUID    NOT NULL REFERENCES %I.scripts(id) ON DELETE CASCADE,
            order_num    INT     NOT NULL,
            name         TEXT    NOT NULL,
            description  TEXT    NOT NULL,
            group_type   TEXT    NOT NULL DEFAULT 'required',
            weight       NUMERIC(4,2) NOT NULL DEFAULT 1.0,
            scoring_type TEXT    NOT NULL DEFAULT 'binary',
            is_active    BOOLEAN NOT NULL DEFAULT TRUE,
            UNIQUE (script_id, order_num)
        )
    $sql$, schema_name, schema_name);

    -- batches
    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.batches (
            id               UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
            status           TEXT    NOT NULL DEFAULT 'uploading',
            total_calls      INT     NOT NULL DEFAULT 0,
            processed_calls  INT     NOT NULL DEFAULT 0,
            call_type_stats  JSONB,
            created_at       BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
            finished_at      BIGINT,
            CONSTRAINT chk_batch_status CHECK (status IN (
                'uploading', 'transcribing', 'evaluating', 'summarizing', 'done', 'failed'
            ))
        )
    $sql$, schema_name);

    -- calls (with batch_id and call_type)
    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.calls (
            id                  UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
            manager_id          UUID    REFERENCES %I.managers(id),
            script_id           UUID    REFERENCES %I.scripts(id),
            batch_id            UUID    REFERENCES %I.batches(id) ON DELETE SET NULL,
            call_type           TEXT,
            status              TEXT    NOT NULL DEFAULT 'queued',
            source              TEXT    NOT NULL DEFAULT 'direct',
            audio_s3_key        TEXT,
            audio_filename      TEXT,
            duration_seconds    INT,
            failed_step         TEXT,
            error_message       TEXT,
            created_at          BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
            finished_at         BIGINT,
            CONSTRAINT chk_status CHECK (status IN (
                'queued', 'processing', 'transcribed_only',
                'pending_review', 'analyzing', 'done', 'failed'
            ))
        )
    $sql$, schema_name, schema_name, schema_name, schema_name);

    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_calls_manager ON %I.calls (manager_id)',
        replace(schema_name, '.', '_'), schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_calls_status ON %I.calls (status)',
        replace(schema_name, '.', '_'), schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_calls_created ON %I.calls (created_at DESC)',
        replace(schema_name, '.', '_'), schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_calls_batch ON %I.calls (batch_id) WHERE batch_id IS NOT NULL',
        replace(schema_name, '.', '_'), schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_calls_type ON %I.calls (call_type) WHERE call_type IS NOT NULL',
        replace(schema_name, '.', '_'), schema_name);

    -- transcriptions
    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.transcriptions (
            call_id         UUID    PRIMARY KEY REFERENCES %I.calls(id) ON DELETE CASCADE,
            raw_text        TEXT,
            cleaned_text    TEXT,
            language        TEXT    DEFAULT 'ru',
            language_prob   NUMERIC(4,3),
            classification  JSONB,
            created_at      BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
        )
    $sql$, schema_name, schema_name);

    -- speaker_metrics
    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.speaker_metrics (
            call_id                 UUID    PRIMARY KEY REFERENCES %I.calls(id) ON DELETE CASCADE,
            manager_talk_ratio      NUMERIC(4,3),
            client_talk_ratio       NUMERIC(4,3),
            silence_ratio           NUMERIC(4,3),
            interruptions_count     INT,
            avg_pause_seconds       NUMERIC(6,2),
            manager_wpm             NUMERIC(6,1),
            client_wpm              NUMERIC(6,1),
            longest_monologue_sec   NUMERIC(6,2),
            created_at              BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
        )
    $sql$, schema_name, schema_name);

    -- quality_scores
    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.quality_scores (
            call_id          UUID    PRIMARY KEY REFERENCES %I.calls(id) ON DELETE CASCADE,
            script_id        UUID    REFERENCES %I.scripts(id),
            overall_score    NUMERIC(5,2),
            required_score   NUMERIC(5,2),
            optional_score   NUMERIC(5,2),
            criteria         JSONB,
            strengths        JSONB,
            weaknesses       JSONB,
            recommendations  JSONB,
            processed_at     BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
        )
    $sql$, schema_name, schema_name, schema_name);

    -- batch_summaries
    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.batch_summaries (
            id           UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
            batch_id     UUID    NOT NULL REFERENCES %I.batches(id) ON DELETE CASCADE,
            scope        TEXT    NOT NULL DEFAULT 'all',
            period_type  TEXT    NOT NULL DEFAULT 'batch',
            content      JSONB,
            created_at   BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
            CONSTRAINT chk_summary_scope CHECK (scope IN ('internal', 'external', 'all')),
            CONSTRAINT chk_summary_period CHECK (period_type IN ('batch', 'daily', 'weekly', 'custom'))
        )
    $sql$, schema_name, schema_name);

    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_batch_summaries_batch ON %I.batch_summaries (batch_id)',
        replace(schema_name, '.', '_'), schema_name);

    -- error_events
    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.error_events (
            id              SERIAL  PRIMARY KEY,
            call_id         UUID    NOT NULL REFERENCES %I.calls(id) ON DELETE CASCADE,
            criterion_id    INT,
            criterion_name  TEXT,
            severity        TEXT    NOT NULL DEFAULT 'medium',
            status          TEXT,
            score           NUMERIC(3,1),
            comment         TEXT,
            quote           TEXT,
            created_at      BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
        )
    $sql$, schema_name, schema_name);

    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_errors_call ON %I.error_events (call_id)',
        replace(schema_name, '.', '_'), schema_name);

    -- usage_log
    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.usage_log (
            id              SERIAL  PRIMARY KEY,
            call_id         UUID    NOT NULL REFERENCES %I.calls(id),
            minutes_billed  NUMERIC(8,2) NOT NULL,
            billed_at       BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
        )
    $sql$, schema_name, schema_name);

    RAISE NOTICE 'Tenant schema % created successfully', schema_name;
END;
$$ LANGUAGE plpgsql;

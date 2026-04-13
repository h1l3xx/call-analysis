-- Period-based manager evaluation (separate from per-call quality_scores)
CREATE OR REPLACE FUNCTION create_manager_evaluations_table(schema_name TEXT) RETURNS void AS $$
BEGIN
    EXECUTE format('
        CREATE TABLE IF NOT EXISTS %I.manager_evaluations (
            id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            manager_id      UUID NOT NULL REFERENCES %I.managers(id) ON DELETE CASCADE,
            period_from     BIGINT,
            period_to       BIGINT,
            call_count      INTEGER NOT NULL DEFAULT 0,
            avg_score       NUMERIC(5,2),
            assessment      JSONB,
            created_at      BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000
        )', schema_name, schema_name);
    EXECUTE format('
        CREATE INDEX IF NOT EXISTS manager_evaluations_manager_id_idx
            ON %I.manager_evaluations (manager_id, created_at DESC)', schema_name);
END;
$$ LANGUAGE plpgsql;

SELECT create_manager_evaluations_table('tenant_sib_standart');

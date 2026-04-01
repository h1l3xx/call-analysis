-- =============================================================================
-- V6: Меняем timestamptz → bigint (Unix ms) в tenant-схемах
-- Аналогично V5 для public, но для динамических tenant-схем
-- =============================================================================

CREATE OR REPLACE FUNCTION public.migrate_tenant_timestamps(schema_name TEXT)
RETURNS VOID AS $$
BEGIN
    -- departments
    EXECUTE format($sql$
        ALTER TABLE %I.departments
            ALTER COLUMN created_at DROP DEFAULT,
            ALTER COLUMN created_at TYPE bigint USING (EXTRACT(EPOCH FROM created_at) * 1000)::bigint,
            ALTER COLUMN created_at SET DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
    $sql$, schema_name);

    -- managers
    EXECUTE format($sql$
        ALTER TABLE %I.managers
            ALTER COLUMN created_at DROP DEFAULT,
            ALTER COLUMN created_at TYPE bigint USING (EXTRACT(EPOCH FROM created_at) * 1000)::bigint,
            ALTER COLUMN created_at SET DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
            ALTER COLUMN updated_at DROP DEFAULT,
            ALTER COLUMN updated_at TYPE bigint USING (EXTRACT(EPOCH FROM updated_at) * 1000)::bigint,
            ALTER COLUMN updated_at SET DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
    $sql$, schema_name);

    -- scripts
    EXECUTE format($sql$
        ALTER TABLE %I.scripts
            ALTER COLUMN created_at DROP DEFAULT,
            ALTER COLUMN created_at TYPE bigint USING (EXTRACT(EPOCH FROM created_at) * 1000)::bigint,
            ALTER COLUMN created_at SET DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
            ALTER COLUMN updated_at DROP DEFAULT,
            ALTER COLUMN updated_at TYPE bigint USING (EXTRACT(EPOCH FROM updated_at) * 1000)::bigint,
            ALTER COLUMN updated_at SET DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
    $sql$, schema_name);

    -- calls
    EXECUTE format($sql$
        ALTER TABLE %I.calls
            ALTER COLUMN created_at DROP DEFAULT,
            ALTER COLUMN created_at TYPE bigint USING (EXTRACT(EPOCH FROM created_at) * 1000)::bigint,
            ALTER COLUMN created_at SET DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
            ALTER COLUMN finished_at DROP DEFAULT,
            ALTER COLUMN finished_at TYPE bigint USING (EXTRACT(EPOCH FROM finished_at) * 1000)::bigint
    $sql$, schema_name);

    -- transcriptions
    EXECUTE format($sql$
        ALTER TABLE %I.transcriptions
            ALTER COLUMN created_at DROP DEFAULT,
            ALTER COLUMN created_at TYPE bigint USING (EXTRACT(EPOCH FROM created_at) * 1000)::bigint,
            ALTER COLUMN created_at SET DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
    $sql$, schema_name);

    -- speaker_metrics
    EXECUTE format($sql$
        ALTER TABLE %I.speaker_metrics
            ALTER COLUMN created_at DROP DEFAULT,
            ALTER COLUMN created_at TYPE bigint USING (EXTRACT(EPOCH FROM created_at) * 1000)::bigint,
            ALTER COLUMN created_at SET DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
    $sql$, schema_name);

    -- quality_scores
    EXECUTE format($sql$
        ALTER TABLE %I.quality_scores
            ALTER COLUMN processed_at DROP DEFAULT,
            ALTER COLUMN processed_at TYPE bigint USING (EXTRACT(EPOCH FROM processed_at) * 1000)::bigint,
            ALTER COLUMN processed_at SET DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
    $sql$, schema_name);

    -- error_events
    EXECUTE format($sql$
        ALTER TABLE %I.error_events
            ALTER COLUMN created_at DROP DEFAULT,
            ALTER COLUMN created_at TYPE bigint USING (EXTRACT(EPOCH FROM created_at) * 1000)::bigint,
            ALTER COLUMN created_at SET DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
    $sql$, schema_name);

    -- usage_log
    EXECUTE format($sql$
        ALTER TABLE %I.usage_log
            ALTER COLUMN billed_at DROP DEFAULT,
            ALTER COLUMN billed_at TYPE bigint USING (EXTRACT(EPOCH FROM billed_at) * 1000)::bigint,
            ALTER COLUMN billed_at SET DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
    $sql$, schema_name);

    RAISE NOTICE 'Timestamps migrated for schema %', schema_name;
END;
$$ LANGUAGE plpgsql;

-- Мигрируем все существующие tenant-схемы
DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN SELECT db_schema FROM public.tenants WHERE is_active = TRUE
    LOOP
        PERFORM public.migrate_tenant_timestamps(rec.db_schema);
    END LOOP;
END;
$$;

DROP FUNCTION IF EXISTS public.migrate_tenant_timestamps(TEXT);

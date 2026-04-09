-- =============================================================================
-- V21: Department + direction policy mapping (script + prompt template).
-- =============================================================================

CREATE OR REPLACE FUNCTION public.ensure_tenant_policy_structures(schema_name TEXT)
RETURNS VOID AS $func$
DECLARE
    default_script UUID;
BEGIN
    IF schema_name !~ '^tenant_[a-z0-9_]+$' THEN
        RAISE EXCEPTION 'Invalid schema name: %. Must match tenant_[a-z0-9_]+', schema_name;
    END IF;

    EXECUTE format(
        'ALTER TABLE %I.calls ADD COLUMN IF NOT EXISTS call_direction TEXT',
        schema_name
    );

    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS idx_%s_calls_direction ON %I.calls (call_direction)',
        replace(schema_name, '.', '_'),
        schema_name
    );

    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.department_call_policies (
            id                 UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
            department_id      UUID    REFERENCES %I.departments(id) ON DELETE CASCADE,
            call_direction     TEXT    NOT NULL,
            script_id          UUID    NOT NULL REFERENCES %I.scripts(id) ON DELETE CASCADE,
            prompt_template_id TEXT    NOT NULL REFERENCES %I.prompt_templates(id) ON DELETE RESTRICT,
            created_at         BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
            updated_at         BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
        )
    $sql$, schema_name, schema_name, schema_name, schema_name);

    EXECUTE format(
        'CREATE UNIQUE INDEX IF NOT EXISTS uq_%s_policy_global_direction ON %I.department_call_policies (call_direction) WHERE department_id IS NULL',
        replace(schema_name, '.', '_'),
        schema_name
    );
    EXECUTE format(
        'CREATE UNIQUE INDEX IF NOT EXISTS uq_%s_policy_department_direction ON %I.department_call_policies (department_id, call_direction) WHERE department_id IS NOT NULL',
        replace(schema_name, '.', '_'),
        schema_name
    );

    EXECUTE format(
        'SELECT id FROM %I.scripts WHERE is_active = true ORDER BY is_default DESC, created_at ASC LIMIT 1',
        schema_name
    ) INTO default_script;

    IF default_script IS NOT NULL THEN
        EXECUTE format(
            'INSERT INTO %I.department_call_policies (department_id, call_direction, script_id, prompt_template_id)
             VALUES (NULL, ''internal'', $1, ''internal_eval'')
             ON CONFLICT DO NOTHING',
            schema_name
        ) USING default_script;

        EXECUTE format(
            'INSERT INTO %I.department_call_policies (department_id, call_direction, script_id, prompt_template_id)
             VALUES (NULL, ''external_incoming'', $1, ''external_eval'')
             ON CONFLICT DO NOTHING',
            schema_name
        ) USING default_script;

        EXECUTE format(
            'INSERT INTO %I.department_call_policies (department_id, call_direction, script_id, prompt_template_id)
             VALUES (NULL, ''external_outgoing'', $1, ''external_eval'')
             ON CONFLICT DO NOTHING',
            schema_name
        ) USING default_script;
    END IF;
END;
$func$ LANGUAGE plpgsql;

DO $$
DECLARE
    tenant RECORD;
BEGIN
    FOR tenant IN SELECT db_schema FROM public.tenants LOOP
        PERFORM public.ensure_tenant_policy_structures(tenant.db_schema);
    END LOOP;
END $$;

-- =============================================================================
-- V29: Internal calls default to no script (evaluation only).
-- =============================================================================

-- 1. Remove script from existing global internal policies for all tenants
DO $$
DECLARE
    tenant RECORD;
BEGIN
    FOR tenant IN SELECT db_schema FROM public.tenants LOOP
        EXECUTE format(
            'UPDATE %I.department_call_policies
             SET script_id = NULL, updated_at = (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
             WHERE department_id IS NULL
               AND second_department_id IS NULL
               AND call_direction IN (''internal_incoming'', ''internal_outgoing'')',
            tenant.db_schema
        );
    END LOOP;
END $$;

-- 2. Update helper function so new tenants also get internal policies without script
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
            id                   UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
            department_id        UUID    REFERENCES %I.departments(id) ON DELETE CASCADE,
            second_department_id UUID    REFERENCES %I.departments(id) ON DELETE CASCADE,
            call_direction       TEXT    NOT NULL,
            script_id            UUID    REFERENCES %I.scripts(id) ON DELETE CASCADE,
            prompt_template_id   TEXT    NOT NULL REFERENCES %I.prompt_templates(id) ON DELETE RESTRICT,
            created_at           BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
            updated_at           BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
        )
    $sql$, schema_name, schema_name, schema_name, schema_name, schema_name);

    EXECUTE format(
        'ALTER TABLE %I.department_call_policies ADD COLUMN IF NOT EXISTS second_department_id UUID',
        schema_name
    );
    EXECUTE format(
        'ALTER TABLE %I.department_call_policies ALTER COLUMN script_id DROP NOT NULL',
        schema_name
    );

    EXECUTE format(
        'CREATE UNIQUE INDEX IF NOT EXISTS uq_%s_policy_global_direction ON %I.department_call_policies (call_direction) WHERE department_id IS NULL AND second_department_id IS NULL',
        replace(schema_name, '.', '_'),
        schema_name
    );
    EXECUTE format(
        'CREATE UNIQUE INDEX IF NOT EXISTS uq_%s_policy_department_single_direction ON %I.department_call_policies (department_id, call_direction) WHERE department_id IS NOT NULL AND second_department_id IS NULL',
        replace(schema_name, '.', '_'),
        schema_name
    );
    EXECUTE format(
        'CREATE UNIQUE INDEX IF NOT EXISTS uq_%s_policy_department_pair_direction ON %I.department_call_policies (department_id, second_department_id, call_direction) WHERE department_id IS NOT NULL AND second_department_id IS NOT NULL',
        replace(schema_name, '.', '_'),
        schema_name
    );

    EXECUTE format(
        'SELECT id FROM %I.scripts WHERE is_active = true ORDER BY is_default DESC, created_at ASC LIMIT 1',
        schema_name
    ) INTO default_script;

    -- Internal calls: no script by default (evaluation only)
    EXECUTE format(
        'INSERT INTO %I.department_call_policies (department_id, second_department_id, call_direction, script_id, prompt_template_id)
         VALUES (NULL, NULL, ''internal_outgoing'', NULL, ''eval_internal_outgoing'')
         ON CONFLICT DO NOTHING',
        schema_name
    );

    EXECUTE format(
        'INSERT INTO %I.department_call_policies (department_id, second_department_id, call_direction, script_id, prompt_template_id)
         VALUES (NULL, NULL, ''internal_incoming'', NULL, ''eval_internal_incoming'')
         ON CONFLICT DO NOTHING',
        schema_name
    );

    -- External calls: use default script
    IF default_script IS NOT NULL THEN
        EXECUTE format(
            'INSERT INTO %I.department_call_policies (department_id, second_department_id, call_direction, script_id, prompt_template_id)
             VALUES (NULL, NULL, ''external_incoming'', $1, ''eval_external_incoming'')
             ON CONFLICT DO NOTHING',
            schema_name
        ) USING default_script;

        EXECUTE format(
            'INSERT INTO %I.department_call_policies (department_id, second_department_id, call_direction, script_id, prompt_template_id)
             VALUES (NULL, NULL, ''external_outgoing'', $1, ''eval_external_outgoing'')
             ON CONFLICT DO NOTHING',
            schema_name
        ) USING default_script;
    END IF;
END;
$func$ LANGUAGE plpgsql;

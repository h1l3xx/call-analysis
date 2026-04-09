-- =============================================================================
-- V25: Helper function for new tenants (internal in/out directional defaults).
-- =============================================================================

CREATE OR REPLACE FUNCTION public.ensure_tenant_internal_direction_defaults(schema_name TEXT)
RETURNS VOID AS $func$
DECLARE
    internal_content TEXT;
    default_script UUID;
BEGIN
    IF schema_name !~ '^tenant_[a-z0-9_]+$' THEN
        RAISE EXCEPTION 'Invalid schema name: %. Must match tenant_[a-z0-9_]+', schema_name;
    END IF;

    EXECUTE format('SELECT content FROM %I.prompt_templates WHERE id = ''internal_eval''', schema_name) INTO internal_content;
    internal_content := COALESCE(internal_content, 'Оцени разговор по 5 критериям эффективного внутреннего общения и сформируй вывод.');

    EXECUTE format(
        'INSERT INTO %I.prompt_templates (id, name, description, content)
         VALUES (''eval_internal_incoming'', ''Оценка входящих внутренних'', ''Шаблон для входящих внутренних звонков'', $1)
         ON CONFLICT (id) DO NOTHING',
        schema_name
    ) USING internal_content;

    EXECUTE format(
        'INSERT INTO %I.prompt_templates (id, name, description, content)
         VALUES (''eval_internal_outgoing'', ''Оценка исходящих внутренних'', ''Шаблон для исходящих внутренних звонков'', $1)
         ON CONFLICT (id) DO NOTHING',
        schema_name
    ) USING internal_content;

    EXECUTE format(
        'SELECT id FROM %I.scripts WHERE is_active = true ORDER BY is_default DESC, created_at ASC LIMIT 1',
        schema_name
    ) INTO default_script;

    IF default_script IS NOT NULL THEN
        EXECUTE format(
            'INSERT INTO %I.department_call_policies (department_id, call_direction, script_id, prompt_template_id)
             VALUES (NULL, ''internal_incoming'', $1, ''eval_internal_incoming'')
             ON CONFLICT DO NOTHING',
            schema_name
        ) USING default_script;

        EXECUTE format(
            'INSERT INTO %I.department_call_policies (department_id, call_direction, script_id, prompt_template_id)
             VALUES (NULL, ''internal_outgoing'', $1, ''eval_internal_outgoing'')
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
        PERFORM public.ensure_tenant_internal_direction_defaults(tenant.db_schema);
    END LOOP;
END $$;

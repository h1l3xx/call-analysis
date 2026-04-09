-- =============================================================================
-- V22: Add directional prompt template IDs.
-- =============================================================================

CREATE OR REPLACE FUNCTION public.ensure_tenant_directional_prompt_templates(schema_name TEXT)
RETURNS VOID AS $func$
DECLARE
    internal_content TEXT;
    external_content TEXT;
BEGIN
    IF schema_name !~ '^tenant_[a-z0-9_]+$' THEN
        RAISE EXCEPTION 'Invalid schema name: %. Must match tenant_[a-z0-9_]+', schema_name;
    END IF;

    EXECUTE format('SELECT content FROM %I.prompt_templates WHERE id = ''internal_eval''', schema_name) INTO internal_content;
    EXECUTE format('SELECT content FROM %I.prompt_templates WHERE id = ''external_eval''', schema_name) INTO external_content;

    internal_content := COALESCE(internal_content, 'Оцени разговор по 5 критериям эффективного внутреннего общения и сформируй вывод.');
    external_content := COALESCE(external_content, 'Оцени каждый критерий и сформируй краткое описание разговора.');

    EXECUTE format(
        'INSERT INTO %I.prompt_templates (id, name, description, content)
         VALUES (''eval_internal'', ''Оценка внутренних (направление)'', ''Шаблон оценки для направления internal'', $1)
         ON CONFLICT (id) DO NOTHING',
        schema_name
    ) USING internal_content;

    EXECUTE format(
        'INSERT INTO %I.prompt_templates (id, name, description, content)
         VALUES (''eval_external_incoming'', ''Оценка входящих внешних'', ''Шаблон оценки для входящих внешних звонков'', $1)
         ON CONFLICT (id) DO NOTHING',
        schema_name
    ) USING external_content;

    EXECUTE format(
        'INSERT INTO %I.prompt_templates (id, name, description, content)
         VALUES (''eval_external_outgoing'', ''Оценка исходящих внешних'', ''Шаблон оценки для исходящих внешних звонков'', $1)
         ON CONFLICT (id) DO NOTHING',
        schema_name
    ) USING external_content;

    EXECUTE format(
        'UPDATE %I.department_call_policies
            SET prompt_template_id = CASE
                WHEN call_direction = ''internal'' THEN ''eval_internal''
                WHEN call_direction = ''external_incoming'' THEN ''eval_external_incoming''
                WHEN call_direction = ''external_outgoing'' THEN ''eval_external_outgoing''
                ELSE prompt_template_id
            END
          WHERE prompt_template_id IN (''internal_eval'', ''external_eval'')',
        schema_name
    );
END;
$func$ LANGUAGE plpgsql;

DO $$
DECLARE
    tenant RECORD;
BEGIN
    FOR tenant IN SELECT db_schema FROM public.tenants LOOP
        PERFORM public.ensure_tenant_directional_prompt_templates(tenant.db_schema);
    END LOOP;
END $$;

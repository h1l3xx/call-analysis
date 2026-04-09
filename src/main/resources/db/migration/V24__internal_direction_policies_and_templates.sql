-- =============================================================================
-- V24: Add internal incoming/outgoing directional templates and policies.
-- =============================================================================

DO $$
DECLARE
    tenant RECORD;
    internal_content TEXT;
    default_script UUID;
BEGIN
    FOR tenant IN SELECT db_schema FROM public.tenants LOOP
        EXECUTE format(
            'SELECT content FROM %I.prompt_templates WHERE id = ''internal_eval''',
            tenant.db_schema
        ) INTO internal_content;

        internal_content := COALESCE(
            internal_content,
            'Оцени разговор по 5 критериям эффективного внутреннего общения и сформируй вывод.'
        );

        EXECUTE format(
            'INSERT INTO %I.prompt_templates (id, name, description, content)
             VALUES (''eval_internal_incoming'', ''Оценка входящих внутренних'', ''Шаблон для входящих внутренних звонков'', $1)
             ON CONFLICT (id) DO NOTHING',
            tenant.db_schema
        ) USING internal_content;

        EXECUTE format(
            'INSERT INTO %I.prompt_templates (id, name, description, content)
             VALUES (''eval_internal_outgoing'', ''Оценка исходящих внутренних'', ''Шаблон для исходящих внутренних звонков'', $1)
             ON CONFLICT (id) DO NOTHING',
            tenant.db_schema
        ) USING internal_content;

        EXECUTE format(
            'UPDATE %I.department_call_policies
                SET call_direction = ''internal_outgoing''
              WHERE call_direction = ''internal''',
            tenant.db_schema
        );

        EXECUTE format(
            'UPDATE %I.department_call_policies
                SET prompt_template_id = ''eval_internal_outgoing''
              WHERE call_direction = ''internal_outgoing''
                AND prompt_template_id IN (''internal_eval'', ''eval_internal'')',
            tenant.db_schema
        );

        EXECUTE format(
            'SELECT id FROM %I.scripts WHERE is_active = true ORDER BY is_default DESC, created_at ASC LIMIT 1',
            tenant.db_schema
        ) INTO default_script;

        IF default_script IS NOT NULL THEN
            EXECUTE format(
                'INSERT INTO %I.department_call_policies (department_id, call_direction, script_id, prompt_template_id)
                 VALUES (NULL, ''internal_incoming'', $1, ''eval_internal_incoming'')
                 ON CONFLICT DO NOTHING',
                tenant.db_schema
            ) USING default_script;

            EXECUTE format(
                'INSERT INTO %I.department_call_policies (department_id, call_direction, script_id, prompt_template_id)
                 VALUES (NULL, ''internal_outgoing'', $1, ''eval_internal_outgoing'')
                 ON CONFLICT DO NOTHING',
                tenant.db_schema
            ) USING default_script;
        END IF;
    END LOOP;
END $$;

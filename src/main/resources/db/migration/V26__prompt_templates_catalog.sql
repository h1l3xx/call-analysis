-- =============================================================================
-- V26: Prompt templates catalog for scalable evaluation templates.
-- =============================================================================

CREATE OR REPLACE FUNCTION public.ensure_tenant_prompt_template_catalog(schema_name TEXT)
RETURNS VOID AS $func$
BEGIN
    IF schema_name !~ '^tenant_[a-z0-9_]+$' THEN
        RAISE EXCEPTION 'Invalid schema name: %. Must match tenant_[a-z0-9_]+', schema_name;
    END IF;

    EXECUTE format('ALTER TABLE %I.prompt_templates ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT ''evaluation''', schema_name);
    EXECUTE format('ALTER TABLE %I.prompt_templates ADD COLUMN IF NOT EXISTS is_system BOOLEAN NOT NULL DEFAULT false', schema_name);

    EXECUTE format(
        'UPDATE %I.prompt_templates
            SET kind = ''evaluation''
          WHERE kind IS NULL OR kind = ''''',
        schema_name
    );

    EXECUTE format(
        'UPDATE %I.prompt_templates
            SET is_system = true
          WHERE id IN (
            ''internal_eval'',
            ''external_eval'',
            ''eval_internal'',
            ''eval_internal_incoming'',
            ''eval_internal_outgoing'',
            ''eval_external_incoming'',
            ''eval_external_outgoing''
          )',
        schema_name
    );
END;
$func$ LANGUAGE plpgsql;

DO $$
DECLARE
    tenant RECORD;
BEGIN
    FOR tenant IN SELECT db_schema FROM public.tenants LOOP
        PERFORM public.ensure_tenant_prompt_template_catalog(tenant.db_schema);
    END LOOP;
END $$;

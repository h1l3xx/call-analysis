-- Update manager_period_eval template kind and add template_id column to manager_evaluations
DO $$
DECLARE
    tenant RECORD;
BEGIN
    FOR tenant IN SELECT db_schema FROM public.tenants LOOP
        -- Fix kind of the system manager eval template
        EXECUTE format(
            'UPDATE %I.prompt_templates SET kind = ''manager_evaluation'' WHERE id = ''manager_period_eval''',
            tenant.db_schema
        );

        -- Add template_id column to manager_evaluations if it does not exist
        EXECUTE format(
            'ALTER TABLE %I.manager_evaluations ADD COLUMN IF NOT EXISTS template_id TEXT',
            tenant.db_schema
        );
    END LOOP;
END $$;

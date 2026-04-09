-- =============================================================================
-- V20: Add call_direction to calls for direction-aware routing.
-- =============================================================================

DO $$
DECLARE
    tenant RECORD;
BEGIN
    FOR tenant IN SELECT db_schema FROM public.tenants LOOP
        EXECUTE format(
            'ALTER TABLE %I.calls ADD COLUMN IF NOT EXISTS call_direction TEXT',
            tenant.db_schema
        );

        EXECUTE format(
            'UPDATE %I.calls
               SET call_direction = CASE
                    WHEN call_type = ''internal'' THEN ''internal''
                    WHEN call_type = ''external'' THEN ''external_incoming''
                    WHEN call_type = ''unknown'' THEN ''unknown''
                    ELSE COALESCE(call_direction, ''unknown'')
                END
             WHERE call_direction IS NULL',
            tenant.db_schema
        );

        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS idx_%s_calls_direction ON %I.calls (call_direction)',
            replace(tenant.db_schema, '.', '_'),
            tenant.db_schema
        );
    END LOOP;
END $$;

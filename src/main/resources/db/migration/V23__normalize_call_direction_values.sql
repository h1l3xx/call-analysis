-- =============================================================================
-- V23: Normalize call_direction to 5-direction model.
-- =============================================================================

DO $$
DECLARE
    tenant RECORD;
BEGIN
    FOR tenant IN SELECT db_schema FROM public.tenants LOOP
        EXECUTE format(
            'UPDATE %I.calls
                SET call_direction = CASE
                    WHEN call_direction = ''internal'' THEN ''internal_outgoing''
                    WHEN call_direction = ''external'' THEN ''external_incoming''
                    ELSE call_direction
                END
              WHERE call_direction IN (''internal'', ''external'')',
            tenant.db_schema
        );
    END LOOP;
END $$;

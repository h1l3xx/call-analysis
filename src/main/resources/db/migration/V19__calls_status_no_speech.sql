-- Add 'no_speech' to allowed call statuses in all existing tenant schemas

DO $$
DECLARE
    schema_rec RECORD;
BEGIN
    FOR schema_rec IN
        SELECT db_schema FROM public.tenants
    LOOP
        EXECUTE format(
            'ALTER TABLE %I.calls DROP CONSTRAINT IF EXISTS chk_status',
            schema_rec.db_schema
        );
        EXECUTE format(
            'ALTER TABLE %I.calls ADD CONSTRAINT chk_status CHECK (status IN (
                ''queued'', ''processing'', ''transcribed_only'',
                ''pending_review'', ''analyzing'', ''done'', ''failed'', ''no_speech''
            ))',
            schema_rec.db_schema
        );
    END LOOP;
END $$;

-- =============================================================================
-- V7: Fix updated_at trigger for bigint timestamps (Unix ms)
-- =============================================================================
--
-- V4 introduced public.set_updated_at() that assigns NOW() (timestamptz).
-- V5 converted updated_at columns to bigint (Unix ms).
-- Without this fix any UPDATE on tables that have updated_at trigger would fail.

CREATE OR REPLACE FUNCTION public.set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;


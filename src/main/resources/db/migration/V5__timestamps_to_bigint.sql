-- =============================================================================
-- V5: Меняем timestamptz → bigint (Unix ms) в public схеме
-- Причина: Exposed работает с Long напрямую, без зависимости от exposed-java-time
-- =============================================================================

ALTER TABLE public.tenants
    ALTER COLUMN created_at DROP DEFAULT,
    ALTER COLUMN created_at TYPE bigint USING (EXTRACT(EPOCH FROM created_at) * 1000)::bigint,
    ALTER COLUMN created_at SET DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
    ALTER COLUMN updated_at DROP DEFAULT,
    ALTER COLUMN updated_at TYPE bigint USING (EXTRACT(EPOCH FROM updated_at) * 1000)::bigint,
    ALTER COLUMN updated_at SET DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint;

ALTER TABLE public.users
    ALTER COLUMN created_at DROP DEFAULT,
    ALTER COLUMN created_at TYPE bigint USING (EXTRACT(EPOCH FROM created_at) * 1000)::bigint,
    ALTER COLUMN created_at SET DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
    ALTER COLUMN updated_at DROP DEFAULT,
    ALTER COLUMN updated_at TYPE bigint USING (EXTRACT(EPOCH FROM updated_at) * 1000)::bigint,
    ALTER COLUMN updated_at SET DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint;

ALTER TABLE public.refresh_tokens
    ALTER COLUMN expires_at DROP DEFAULT,
    ALTER COLUMN expires_at TYPE bigint USING (EXTRACT(EPOCH FROM expires_at) * 1000)::bigint,
    ALTER COLUMN created_at DROP DEFAULT,
    ALTER COLUMN created_at TYPE bigint USING (EXTRACT(EPOCH FROM created_at) * 1000)::bigint,
    ALTER COLUMN created_at SET DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
    ALTER COLUMN revoked_at DROP DEFAULT,
    ALTER COLUMN revoked_at TYPE bigint USING (EXTRACT(EPOCH FROM revoked_at) * 1000)::bigint;

ALTER TABLE public.plans
    ALTER COLUMN created_at DROP DEFAULT,
    ALTER COLUMN created_at TYPE bigint USING (EXTRACT(EPOCH FROM created_at) * 1000)::bigint,
    ALTER COLUMN created_at SET DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint;

ALTER TABLE public.tenant_subscriptions
    ALTER COLUMN created_at DROP DEFAULT,
    ALTER COLUMN created_at TYPE bigint USING (EXTRACT(EPOCH FROM created_at) * 1000)::bigint,
    ALTER COLUMN created_at SET DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint;

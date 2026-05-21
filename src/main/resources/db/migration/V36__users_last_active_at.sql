-- Добавляем колонку last_active_at в таблицу пользователей.
-- Обновляется при каждом аутентифицированном запросе к API.
ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS last_active_at BIGINT;

CREATE INDEX IF NOT EXISTS idx_users_last_active_at
    ON public.users (last_active_at DESC NULLS LAST);

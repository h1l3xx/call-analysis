-- =============================================================================
-- V4: Триггеры updated_at + полнотекстовый поиск
-- =============================================================================

-- Универсальная функция триггера updated_at
CREATE OR REPLACE FUNCTION public.set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Триггеры на public таблицах
CREATE TRIGGER trg_tenants_updated_at
    BEFORE UPDATE ON public.tenants
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON public.users
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- Функция для автоматического биллинга минут при завершении звонка
-- Вызывается Ktor после сохранения результата pipeline
CREATE OR REPLACE FUNCTION public.bill_call_minutes(
    p_tenant_id UUID,
    p_call_id   UUID,
    p_duration_seconds INT
)
RETURNS NUMERIC AS $$
DECLARE
    v_minutes NUMERIC;
BEGIN
    -- Округляем вверх до целой минуты
    v_minutes := CEIL(p_duration_seconds::NUMERIC / 60);

    -- Обновляем счётчик в подписке
    UPDATE public.tenant_subscriptions
    SET minutes_used = minutes_used + v_minutes
    WHERE tenant_id = p_tenant_id AND is_current = TRUE;

    RETURN v_minutes;
END;
$$ LANGUAGE plpgsql;

GRANT EXECUTE ON FUNCTION public.bill_call_minutes(UUID, UUID, INT) TO malikov_app;
GRANT EXECUTE ON FUNCTION public.set_updated_at() TO malikov_app;

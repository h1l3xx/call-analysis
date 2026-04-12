-- =============================================================================
-- V31: Поддержка нескольких телефонных номеров у сотрудника.
--   • Новая таблица manager_phone_numbers (id, manager_id, phone_number, label, is_primary)
--   • Миграция существующих phone_number из managers → новая таблица (is_primary = true)
--   • Второй номер Гузеевой Валентины Сергеевны
-- =============================================================================

DO $$
DECLARE
    tenant RECORD;
BEGIN
    FOR tenant IN SELECT db_schema FROM public.tenants LOOP

        -- 1. Создать таблицу
        EXECUTE format($sql$
            CREATE TABLE IF NOT EXISTS %I.manager_phone_numbers (
                id           UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
                manager_id   UUID    NOT NULL REFERENCES %I.managers(id) ON DELETE CASCADE,
                phone_number TEXT    NOT NULL,
                label        TEXT,
                is_primary   BOOLEAN NOT NULL DEFAULT FALSE,
                created_at   BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
                UNIQUE (manager_id, phone_number)
            )
        $sql$, tenant.db_schema, tenant.db_schema);

        EXECUTE format($sql$
            CREATE INDEX IF NOT EXISTS idx_%s_mgr_phones_phone
                ON %I.manager_phone_numbers (phone_number)
        $sql$, replace(tenant.db_schema, '.', '_'), tenant.db_schema);

        EXECUTE format($sql$
            CREATE INDEX IF NOT EXISTS idx_%s_mgr_phones_manager
                ON %I.manager_phone_numbers (manager_id)
        $sql$, replace(tenant.db_schema, '.', '_'), tenant.db_schema);

        -- 2. Перенести существующие phone_number как основные
        EXECUTE format($sql$
            INSERT INTO %I.manager_phone_numbers (manager_id, phone_number, is_primary, created_at)
            SELECT id, phone_number, TRUE, created_at
            FROM %I.managers
            WHERE phone_number IS NOT NULL AND phone_number != ''
            ON CONFLICT (manager_id, phone_number) DO NOTHING
        $sql$, tenant.db_schema, tenant.db_schema);

    END LOOP;
END $$;

-- 3. Второй номер Гузеевой (tenant_sib_standart)
INSERT INTO tenant_sib_standart.manager_phone_numbers (manager_id, phone_number, label, is_primary, created_at)
SELECT m.id, '79086609121', 'Дополнительный', FALSE, (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
FROM tenant_sib_standart.managers m
JOIN public.users u ON u.id = m.user_id
WHERE u.full_name = 'Гузеева Валентина Сергеевна'
ON CONFLICT (manager_id, phone_number) DO NOTHING;

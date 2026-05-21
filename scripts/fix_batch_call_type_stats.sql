-- Пересчитывает callTypeStats для батчей, у которых статистика нулевая / NULL,
-- хотя в таблице calls уже проставлены типы звонков.
--
-- Запуск на сервере (один раз):
--   docker exec -i malikov_postgres \
--     psql -U malikov -d malikov \
--     -f /tmp/fix_batch_call_type_stats.sql
--
-- Скрипт безопасен — использует транзакцию.
-- Чтобы только посмотреть без изменений — раскомментируй ROLLBACK внизу.

BEGIN;

-- Временная функция для обработки одной схемы.
-- Вызывается ниже для каждого тенанта из public.tenants.
CREATE OR REPLACE FUNCTION _fix_batch_stats(p_schema text)
RETURNS TABLE(batch_id text, internal int, ext_in int, ext_out int, unknown int) AS
$$
DECLARE
    v_sql text;
BEGIN
    v_sql := format($sql$
        WITH call_counts AS (
            SELECT
                c.batch_id,
                COUNT(*) FILTER (WHERE c.call_type = 'internal')                              AS cnt_internal,
                COUNT(*) FILTER (WHERE c.call_type IN ('external_incoming','external'))        AS cnt_ext_in,
                COUNT(*) FILTER (WHERE c.call_type = 'external_outgoing')                     AS cnt_ext_out,
                COUNT(*) FILTER (WHERE c.call_type NOT IN (
                    'internal','external_incoming','external','external_outgoing'
                ) OR c.call_type IS NULL)                                                      AS cnt_unknown
            FROM %I.calls c
            WHERE c.batch_id IS NOT NULL
              AND c.call_type IS NOT NULL
              AND c.call_type <> 'unknown'
            GROUP BY c.batch_id
        ),
        broken_batches AS (
            SELECT b.id
            FROM %I.batches b
            WHERE
                b.call_type_stats IS NULL
                OR (
                    (b.call_type_stats->>'internal')::int        = 0
                    AND (b.call_type_stats->>'externalIncoming')::int = 0
                    AND (b.call_type_stats->>'externalOutgoing')::int = 0
                )
        )
        UPDATE %I.batches b
        SET call_type_stats = jsonb_build_object(
            'internal',         cc.cnt_internal,
            'externalIncoming', cc.cnt_ext_in,
            'externalOutgoing', cc.cnt_ext_out,
            'unknown',          cc.cnt_unknown
        )
        FROM call_counts cc
        JOIN broken_batches bb ON bb.id = cc.batch_id
        WHERE b.id = cc.batch_id
        RETURNING
            b.id::text,
            (b.call_type_stats->>'internal')::int,
            (b.call_type_stats->>'externalIncoming')::int,
            (b.call_type_stats->>'externalOutgoing')::int,
            (b.call_type_stats->>'unknown')::int
    $sql$, p_schema, p_schema, p_schema);

    RETURN QUERY EXECUTE v_sql;
END;
$$ LANGUAGE plpgsql;


-- Основной блок: обходим все активные тенанты
DO $$
DECLARE
    r_tenant  RECORD;
    r_batch   RECORD;
    v_total   int := 0;
    v_schema  text;
BEGIN
    FOR r_tenant IN
        SELECT db_schema FROM public.tenants WHERE is_active = TRUE ORDER BY db_schema
    LOOP
        v_schema := r_tenant.db_schema;
        RAISE NOTICE '→ Схема: %', v_schema;

        FOR r_batch IN
            SELECT * FROM _fix_batch_stats(v_schema)
        LOOP
            RAISE NOTICE '  batch % → internal=%, extIn=%, extOut=%, unknown=%',
                r_batch.batch_id,
                r_batch.internal,
                r_batch.ext_in,
                r_batch.ext_out,
                r_batch.unknown;
            v_total := v_total + 1;
        END LOOP;

        IF NOT FOUND THEN
            RAISE NOTICE '  Сломанных батчей не найдено.';
        END IF;
    END LOOP;

    RAISE NOTICE '';
    RAISE NOTICE 'Итого обновлено: % батч(ей)', v_total;
END;
$$;

-- Удаляем вспомогательную функцию
DROP FUNCTION IF EXISTS _fix_batch_stats(text);

COMMIT;
-- Если хочешь только посмотреть — замени COMMIT выше на ROLLBACK:
-- ROLLBACK;

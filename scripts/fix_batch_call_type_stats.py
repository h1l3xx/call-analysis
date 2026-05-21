#!/usr/bin/env python3
"""
Исправляет callTypeStats у батчей, у которых все поля статистики равны 0
(или NULL), хотя звонки в таблице calls уже имеют проставленный call_type.

Проблема: refreshTypeStats раньше считала только звонки в статусах
done/transcribed_only/no_speech/failed. Сразу после bulk-upload все звонки
в статусе queued, поэтому статистика обнулялась и больше не пересчитывалась.

Скрипт перебирает все тенантские схемы из public.tenants, находит
«сломанные» батчи и обновляет их callTypeStats по реальным данным из calls.

Использование:
    python fix_batch_call_type_stats.py
    python fix_batch_call_type_stats.py --dry-run      # только показать, без записи
    python fix_batch_call_type_stats.py --schema myschema  # только одна схема

Переменные окружения (или .env):
    DB_URL      — строка подключения psycopg2, например:
                  postgresql://user:pass@localhost:5432/dbname
                  (по умолчанию postgresql://malikov:malikov_dev@localhost:5432/malikov)
"""

import argparse
import json
import os
import sys

try:
    import psycopg2
    import psycopg2.extras
except ImportError:
    sys.exit("psycopg2 не установлен: pip install psycopg2-binary")


# ---------------------------------------------------------------------------
# Подключение
# ---------------------------------------------------------------------------

def get_conn():
    db_url = os.getenv(
        "DB_URL",
        "postgresql://malikov:malikov_dev@localhost:5432/malikov",
    )
    return psycopg2.connect(db_url)


# ---------------------------------------------------------------------------
# Основная логика
# ---------------------------------------------------------------------------

def get_schemas(cur) -> list[str]:
    """Вернуть список db_schema всех активных тенантов."""
    cur.execute("SELECT db_schema FROM public.tenants WHERE is_active = TRUE ORDER BY db_schema")
    return [row[0] for row in cur.fetchall()]


def find_broken_batches(cur, schema: str) -> list[str]:
    """
    Вернуть UUID батчей, у которых callTypeStats нулевой / NULL,
    но при этом в таблице calls есть хотя бы один звонок с известным callType.
    """
    cur.execute(f"""
        SELECT b.id::text
        FROM {schema}.batches b
        WHERE
            -- NULL или все поля == 0
            (
                b.call_type_stats IS NULL
                OR (
                    (b.call_type_stats->>'internal')::int       = 0
                    AND (b.call_type_stats->>'externalIncoming')::int = 0
                    AND (b.call_type_stats->>'externalOutgoing')::int = 0
                )
            )
            -- есть хотя бы один звонок с известным типом
            AND EXISTS (
                SELECT 1 FROM {schema}.calls c
                WHERE c.batch_id = b.id
                  AND c.call_type IS NOT NULL
                  AND c.call_type <> 'unknown'
            )
        ORDER BY b.created_at DESC
    """)
    return [row[0] for row in cur.fetchall()]


def compute_stats(cur, schema: str, batch_id: str) -> dict:
    """Посчитать распределение типов звонков для батча из таблицы calls."""
    cur.execute(f"""
        SELECT
            COALESCE(call_type, 'unknown') AS ct,
            COUNT(*)::int                 AS cnt
        FROM {schema}.calls
        WHERE batch_id = %s::uuid
        GROUP BY 1
    """, (batch_id,))
    rows = {r[0]: r[1] for r in cur.fetchall()}

    internal          = rows.get("internal", 0)
    external_incoming = rows.get("external_incoming", rows.get("external", 0))
    external_outgoing = rows.get("external_outgoing", 0)
    known_sum         = internal + external_incoming + external_outgoing
    total             = sum(rows.values())
    unknown           = total - known_sum

    return {
        "internal":         internal,
        "externalIncoming": external_incoming,
        "externalOutgoing": external_outgoing,
        "unknown":          max(0, unknown),
    }


def fix_schema(cur, schema: str, dry_run: bool) -> int:
    """Исправить все сломанные батчи в схеме. Вернуть кол-во обновлённых."""
    broken = find_broken_batches(cur, schema)
    if not broken:
        return 0

    fixed = 0
    for batch_id in broken:
        stats = compute_stats(cur, schema, batch_id)
        total = stats["internal"] + stats["externalIncoming"] + stats["externalOutgoing"] + stats["unknown"]

        print(
            f"  [{schema}] batch {batch_id} → "
            f"internal={stats['internal']}, "
            f"extIn={stats['externalIncoming']}, "
            f"extOut={stats['externalOutgoing']}, "
            f"unknown={stats['unknown']}  (total={total})"
        )

        if not dry_run:
            cur.execute(f"""
                UPDATE {schema}.batches
                SET call_type_stats = %s::jsonb
                WHERE id = %s::uuid
            """, (json.dumps(stats), batch_id))
            fixed += 1
        else:
            fixed += 1  # считаем как «будет исправлен»

    return fixed


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Пересчитать callTypeStats для сломанных батчей")
    parser.add_argument("--dry-run", action="store_true", help="Только показать изменения, не писать в БД")
    parser.add_argument("--schema", metavar="SCHEMA", help="Обработать только указанную схему")
    args = parser.parse_args()

    if args.dry_run:
        print("⚠️  DRY-RUN: изменения в БД не сохраняются\n")

    conn = get_conn()
    try:
        with conn:
            with conn.cursor() as cur:
                schemas = [args.schema] if args.schema else get_schemas(cur)

                if not schemas:
                    print("Тенанты не найдены.")
                    return

                total_fixed = 0
                for schema in schemas:
                    print(f"\n→ Схема: {schema}")
                    n = fix_schema(cur, schema, dry_run=args.dry_run)
                    if n:
                        print(f"  {'Будет обновлено' if args.dry_run else 'Обновлено'}: {n} батч(ей)")
                    else:
                        print("  Сломанных батчей не найдено.")
                    total_fixed += n

                print(f"\n{'Итого (dry-run)' if args.dry_run else 'Итого обновлено'}: {total_fixed} батч(ей)")

                if args.dry_run:
                    conn.rollback()

    finally:
        conn.close()


if __name__ == "__main__":
    main()

-- =============================================================================
-- V17: Add prompt_templates table for per-tenant LLM prompt customization.
-- =============================================================================

-- Default prompt contents (used for seeding)
DO $$
DECLARE
    tenant RECORD;
    system_prompt_default TEXT := 'Ты — эксперт по оценке качества телефонных разговоров. Отвечай ТОЛЬКО в формате JSON.';
    internal_eval_default TEXT := 'Проанализируй следующий ВНУТРЕННИЙ телефонный разговор между сотрудниками компании.

Транскрипция:
---
{transcription}
---

Оцени разговор по 5 критериям эффективного внутреннего общения:

1. **Ясность и структурированность** (0–100): чёткость формулировок, логика изложения, отсутствие двусмысленности.
2. **Результативность** (0–100): наличие конкретных решений, action items, ответственных и дедлайнов.
3. **Профессионализм** (0–100): деловой тон, отсутствие конфликтности и эмоциональных срывов, уважительное общение.
4. **Эффективность времени** (0–100): отношение полезного содержания к общей длительности, отсутствие уходов от темы.
5. **Соблюдение процедур** (0–100): следование регламентам, корректная эскалация при необходимости, фиксация договорённостей.

Также напиши краткое описание звонка (2–3 предложения): кто звонил, по какому поводу, чем закончился.

Ответ СТРОГО в JSON формате:
{
  "summary": "<краткое описание: кто звонил, тема, итог — 2-3 предложения>",
  "overall_score": <число от 0 до 100>,
  "criteria_scores": {
    "clarity": {"score": <0–100>, "comment": "<пояснение>"},
    "effectiveness": {"score": <0–100>, "comment": "<пояснение>"},
    "professionalism": {"score": <0–100>, "comment": "<пояснение>"},
    "time_efficiency": {"score": <0–100>, "comment": "<пояснение>"},
    "procedures": {"score": <0–100>, "comment": "<пояснение>"}
  },
  "action_items": ["конкретный action item 1", ...],
  "strengths": ["сильная сторона 1", ...],
  "weaknesses": ["слабая сторона 1", ...],
  "recommendations": ["рекомендация 1", ...]
}';
    external_eval_default TEXT := 'Проанализируй следующий разговор менеджера с клиентом по скрипту "{scriptName}".

Транскрипция:
---
{transcription}
---

Критерии оценки:
{criteria}

Оцени каждый критерий. Также напиши краткое описание звонка (2–3 предложения): кто обратился, с какой целью, чем закончился разговор.

Ответ СТРОГО в JSON формате:
{
  "summary": "<краткое описание: кто обратился, цель, итог — 2-3 предложения>",
  "overall_score": <число от 0 до 100>,
  "criteria_evaluations": [
    {
      "id": <номер критерия>,
      "name": "<название>",
      "score": <0.0, 0.5 или 1.0>,
      "comment": "<комментарий>",
      "relevant": <true/false>
    }
  ],
  "strengths": ["сильная сторона 1", ...],
  "weaknesses": ["слабая сторона 1", ...],
  "recommendations": ["рекомендация 1", ...]
}';
BEGIN
    FOR tenant IN SELECT db_schema FROM public.tenants LOOP
        EXECUTE format($sql$
            CREATE TABLE IF NOT EXISTS %I.prompt_templates (
                id          TEXT    PRIMARY KEY,
                name        TEXT    NOT NULL,
                description TEXT,
                content     TEXT    NOT NULL,
                updated_at  BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
            )
        $sql$, tenant.db_schema);

        EXECUTE format(
            'INSERT INTO %I.prompt_templates (id, name, description, content) VALUES ($1, $2, $3, $4) ON CONFLICT (id) DO NOTHING',
            tenant.db_schema
        ) USING
            'system',
            'Системный промпт',
            'Роль и поведение LLM. Не содержит плейсхолдеров.',
            system_prompt_default;

        EXECUTE format(
            'INSERT INTO %I.prompt_templates (id, name, description, content) VALUES ($1, $2, $3, $4) ON CONFLICT (id) DO NOTHING',
            tenant.db_schema
        ) USING
            'internal_eval',
            'Оценка внутренних звонков',
            'Шаблон для оценки звонков между сотрудниками. Плейсхолдер: {transcription}',
            internal_eval_default;

        EXECUTE format(
            'INSERT INTO %I.prompt_templates (id, name, description, content) VALUES ($1, $2, $3, $4) ON CONFLICT (id) DO NOTHING',
            tenant.db_schema
        ) USING
            'external_eval',
            'Оценка внешних звонков',
            'Шаблон для оценки звонков менеджер-клиент. Плейсхолдеры: {transcription}, {criteria}, {scriptName}',
            external_eval_default;

        EXECUTE format('GRANT ALL ON %I.prompt_templates TO malikov_app', tenant.db_schema);
    END LOOP;
END $$;

-- Update create_tenant_schema to include prompt_templates for new tenants
CREATE OR REPLACE FUNCTION public.create_tenant_schema(schema_name TEXT)
RETURNS VOID AS $func$
BEGIN
    IF schema_name !~ '^tenant_[a-z0-9_]+$' THEN
        RAISE EXCEPTION 'Invalid schema name: %. Must match tenant_[a-z0-9_]+', schema_name;
    END IF;

    EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I', schema_name);

    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.departments (
            id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
            name        TEXT        NOT NULL,
            description TEXT,
            is_active   BOOLEAN     DEFAULT TRUE,
            created_at  BIGINT      NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
        )
    $sql$, schema_name);

    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.department_leads (
            user_id       UUID   NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
            department_id UUID   NOT NULL REFERENCES %I.departments(id) ON DELETE CASCADE,
            created_at    BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
            PRIMARY KEY (user_id, department_id)
        )
    $sql$, schema_name, schema_name);

    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.managers (
            id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
            user_id         UUID        NOT NULL REFERENCES public.users(id),
            department_id   UUID        REFERENCES %I.departments(id),
            extension       TEXT,
            phone_number    TEXT,
            is_active       BOOLEAN     DEFAULT TRUE,
            created_at      BIGINT      NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
            updated_at      BIGINT      NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
        )
    $sql$, schema_name, schema_name);

    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.scripts (
            id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
            name        TEXT        NOT NULL,
            call_type   TEXT        NOT NULL DEFAULT 'incoming',
            description TEXT,
            is_active   BOOLEAN     DEFAULT TRUE,
            is_default  BOOLEAN     DEFAULT FALSE,
            created_at  BIGINT      NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
            updated_at  BIGINT      NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
        )
    $sql$, schema_name);

    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.criteria (
            id          SERIAL      PRIMARY KEY,
            script_id   UUID        NOT NULL REFERENCES %I.scripts(id) ON DELETE CASCADE,
            order_num   INT         NOT NULL,
            name        TEXT        NOT NULL,
            description TEXT        NOT NULL DEFAULT '',
            group_type  TEXT        NOT NULL DEFAULT 'required',
            weight      NUMERIC(4,2) DEFAULT 1.0,
            scoring_type TEXT       DEFAULT 'binary',
            is_active   BOOLEAN     DEFAULT TRUE
        )
    $sql$, schema_name, schema_name);

    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.calls (
            id                  UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
            manager_id          UUID    REFERENCES %I.managers(id),
            second_manager_id   UUID    REFERENCES %I.managers(id),
            script_id           UUID    REFERENCES %I.scripts(id),
            batch_id            UUID,
            call_type           TEXT,
            status              TEXT    NOT NULL DEFAULT 'queued',
            source              TEXT    NOT NULL DEFAULT 'direct',
            audio_s3_key        TEXT,
            audio_filename      TEXT,
            duration_seconds    INT,
            failed_step         TEXT,
            error_message       TEXT,
            created_at          BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
            finished_at         BIGINT
        )
    $sql$, schema_name, schema_name, schema_name, schema_name);

    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_calls_status ON %I.calls (status)',
        replace(schema_name, '.', '_'), schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_calls_manager ON %I.calls (manager_id)',
        replace(schema_name, '.', '_'), schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_calls_batch ON %I.calls (batch_id)',
        replace(schema_name, '.', '_'), schema_name);

    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.transcriptions (
            call_id         UUID    PRIMARY KEY REFERENCES %I.calls(id) ON DELETE CASCADE,
            raw_text        TEXT,
            cleaned_text    TEXT,
            language        TEXT    DEFAULT 'ru',
            language_prob   NUMERIC(4,3),
            classification  JSONB,
            speaker_turns   JSONB,
            created_at      BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
        )
    $sql$, schema_name, schema_name);

    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.speaker_metrics (
            call_id                 UUID    PRIMARY KEY REFERENCES %I.calls(id) ON DELETE CASCADE,
            manager_talk_ratio      NUMERIC(4,3),
            client_talk_ratio       NUMERIC(4,3),
            silence_ratio           NUMERIC(4,3),
            interruptions_count     INT,
            avg_pause_seconds       NUMERIC(6,2),
            manager_wpm             NUMERIC(6,1),
            client_wpm              NUMERIC(6,1),
            longest_monologue_sec   NUMERIC(6,2),
            created_at              BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
        )
    $sql$, schema_name, schema_name);

    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.quality_scores (
            call_id          UUID    PRIMARY KEY REFERENCES %I.calls(id) ON DELETE CASCADE,
            script_id        UUID    REFERENCES %I.scripts(id),
            overall_score    NUMERIC(5,2),
            required_score   NUMERIC(5,2),
            optional_score   NUMERIC(5,2),
            criteria         JSONB,
            strengths        JSONB,
            weaknesses       JSONB,
            recommendations  JSONB,
            summary          TEXT,
            processed_at     BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
        )
    $sql$, schema_name, schema_name, schema_name);

    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.error_events (
            id              SERIAL  PRIMARY KEY,
            call_id         UUID    NOT NULL REFERENCES %I.calls(id) ON DELETE CASCADE,
            criterion_id    INT,
            criterion_name  TEXT,
            severity        TEXT    DEFAULT 'medium',
            status          TEXT,
            score           NUMERIC(4,2),
            comment         TEXT,
            quote           TEXT,
            created_at      BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
        )
    $sql$, schema_name, schema_name);

    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.usage_log (
            id              SERIAL  PRIMARY KEY,
            call_id         UUID    NOT NULL REFERENCES %I.calls(id) ON DELETE CASCADE,
            minutes_billed  NUMERIC(10,2) NOT NULL,
            billed_at       BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
        )
    $sql$, schema_name, schema_name);

    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.batches (
            id               UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
            status           TEXT    NOT NULL DEFAULT 'uploading',
            total_calls      INT     NOT NULL DEFAULT 0,
            processed_calls  INT     NOT NULL DEFAULT 0,
            call_type_stats  JSONB,
            created_at       BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint,
            finished_at      BIGINT
        )
    $sql$, schema_name);

    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.batch_summaries (
            id          UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
            batch_id    UUID    NOT NULL REFERENCES %I.batches(id) ON DELETE CASCADE,
            scope       TEXT    NOT NULL DEFAULT 'all',
            period_type TEXT    NOT NULL DEFAULT 'batch',
            content     JSONB,
            created_at  BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
        )
    $sql$, schema_name, schema_name);

    EXECUTE format($sql$
        CREATE TABLE IF NOT EXISTS %I.prompt_templates (
            id          TEXT    PRIMARY KEY,
            name        TEXT    NOT NULL,
            description TEXT,
            content     TEXT    NOT NULL,
            updated_at  BIGINT  NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint
        )
    $sql$, schema_name);

    -- Seed default prompt templates
    EXECUTE format($sql$
        INSERT INTO %I.prompt_templates (id, name, description, content) VALUES
        ('system', 'Системный промпт', 'Роль и поведение LLM. Не содержит плейсхолдеров.',
         'Ты — эксперт по оценке качества телефонных разговоров. Отвечай ТОЛЬКО в формате JSON.'),
        ('internal_eval', 'Оценка внутренних звонков', 'Шаблон для оценки звонков между сотрудниками. Плейсхолдер: {transcription}',
         'Проанализируй следующий ВНУТРЕННИЙ телефонный разговор между сотрудниками компании.

Транскрипция:
---
{transcription}
---

Оцени разговор по 5 критериям эффективного внутреннего общения:

1. **Ясность и структурированность** (0–100): чёткость формулировок, логика изложения, отсутствие двусмысленности.
2. **Результативность** (0–100): наличие конкретных решений, action items, ответственных и дедлайнов.
3. **Профессионализм** (0–100): деловой тон, отсутствие конфликтности и эмоциональных срывов, уважительное общение.
4. **Эффективность времени** (0–100): отношение полезного содержания к общей длительности, отсутствие уходов от темы.
5. **Соблюдение процедур** (0–100): следование регламентам, корректная эскалация при необходимости, фиксация договорённостей.

Также напиши краткое описание звонка (2–3 предложения): кто звонил, по какому поводу, чем закончился.

Ответ СТРОГО в JSON формате:
{
  "summary": "<краткое описание: кто звонил, тема, итог — 2-3 предложения>",
  "overall_score": <число от 0 до 100>,
  "criteria_scores": {
    "clarity": {"score": <0–100>, "comment": "<пояснение>"},
    "effectiveness": {"score": <0–100>, "comment": "<пояснение>"},
    "professionalism": {"score": <0–100>, "comment": "<пояснение>"},
    "time_efficiency": {"score": <0–100>, "comment": "<пояснение>"},
    "procedures": {"score": <0–100>, "comment": "<пояснение>"}
  },
  "action_items": ["конкретный action item 1", ...],
  "strengths": ["сильная сторона 1", ...],
  "weaknesses": ["слабая сторона 1", ...],
  "recommendations": ["рекомендация 1", ...]
}'),
        ('external_eval', 'Оценка внешних звонков', 'Шаблон для оценки звонков менеджер-клиент. Плейсхолдеры: {transcription}, {criteria}, {scriptName}',
         'Проанализируй следующий разговор менеджера с клиентом по скрипту "{scriptName}".

Транскрипция:
---
{transcription}
---

Критерии оценки:
{criteria}

Оцени каждый критерий. Также напиши краткое описание звонка (2–3 предложения): кто обратился, с какой целью, чем закончился разговор.

Ответ СТРОГО в JSON формате:
{
  "summary": "<краткое описание: кто обратился, цель, итог — 2-3 предложения>",
  "overall_score": <число от 0 до 100>,
  "criteria_evaluations": [
    {
      "id": <номер критерия>,
      "name": "<название>",
      "score": <0.0, 0.5 или 1.0>,
      "comment": "<комментарий>",
      "relevant": <true/false>
    }
  ],
  "strengths": ["сильная сторона 1", ...],
  "weaknesses": ["слабая сторона 1", ...],
  "recommendations": ["рекомендация 1", ...]
}')
        ON CONFLICT (id) DO NOTHING
    $sql$, schema_name);

    EXECUTE format('GRANT ALL ON ALL TABLES IN SCHEMA %I TO malikov_app', schema_name);
    EXECUTE format('GRANT ALL ON ALL SEQUENCES IN SCHEMA %I TO malikov_app', schema_name);

END;
$func$ LANGUAGE plpgsql;

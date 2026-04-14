-- Add manager_period_eval prompt template for manager profile evaluations
DO $$
DECLARE
    tenant RECORD;
    default_content TEXT := 'Проанализируй работу сотрудника за период по следующим аспектам:

1. Общий уровень коммуникации: чёткость речи, профессиональный тон, культура общения.
2. Результативность: насколько звонки заканчиваются конкретными договорённостями, решениями или следующими шагами.
3. Соблюдение стандартов: следование скриптам, регламентам, фиксация информации.
4. Систематические проблемы: повторяющиеся ошибки или недостатки, требующие внимания.
5. Точки роста: конкретные навыки или поведение, которые стоит улучшить.

Определи уровень работы сотрудника: high (выше ожиданий), medium (соответствует ожиданиям), low (требует улучшений).';
BEGIN
    FOR tenant IN SELECT db_schema FROM public.tenants LOOP
        EXECUTE format(
            'INSERT INTO %I.prompt_templates (id, name, description, content, kind, is_system)
             VALUES (
                ''manager_period_eval'',
                ''Итоговая оценка сотрудника'',
                ''Промпт для формирования итогового LLM-отчёта по сотруднику за выбранный период'',
                $1,
                ''evaluation'',
                true
             )
             ON CONFLICT (id) DO NOTHING',
            tenant.db_schema
        ) USING default_content;
    END LOOP;
END $$;

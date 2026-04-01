-- =============================================================================
-- V3: Dev-тенант для локальной разработки
-- =============================================================================

-- Создаём тенант
INSERT INTO public.tenants (id, slug, name, db_schema) VALUES (
    '00000000-0000-0000-0000-000000000001',
    'dev-clinic',
    'Dev Клиника (локальная разработка)',
    'tenant_dev_clinic'
);

-- Подписка на план Business
INSERT INTO public.tenant_subscriptions (tenant_id, plan_id, period_start, period_end)
SELECT
    '00000000-0000-0000-0000-000000000001',
    id,
    DATE_TRUNC('month', NOW())::DATE,
    (DATE_TRUNC('month', NOW()) + INTERVAL '1 month - 1 day')::DATE
FROM public.plans WHERE name = 'Business';

-- Создаём схему для dev-тенанта
SELECT public.create_tenant_schema('tenant_dev_clinic');

-- Права приложению на новую схему
GRANT ALL ON SCHEMA tenant_dev_clinic TO malikov_app;
GRANT ALL ON ALL TABLES IN SCHEMA tenant_dev_clinic TO malikov_app;
GRANT ALL ON ALL SEQUENCES IN SCHEMA tenant_dev_clinic TO malikov_app;

-- -------------------------------------------
-- Пользователи dev-тенанта
-- -------------------------------------------

-- CLIENT_ADMIN
INSERT INTO public.users (id, tenant_id, email, password_hash, full_name, role) VALUES (
    '00000000-0000-0000-0000-000000000010',
    '00000000-0000-0000-0000-000000000001',
    'admin@dev-clinic.ru',
    '$2a$12$xZABa2FS44DMwAWuyuvQZuL0h7ycFzXtAFaVbgD9UrOdeTDHkN09S',  -- changeme123
    'Администратор клиники',
    'CLIENT_ADMIN'
);

-- TEAM_LEAD
INSERT INTO public.users (id, tenant_id, email, password_hash, full_name, role) VALUES (
    '00000000-0000-0000-0000-000000000011',
    '00000000-0000-0000-0000-000000000001',
    'lead@dev-clinic.ru',
    '$2a$12$xZABa2FS44DMwAWuyuvQZuL0h7ycFzXtAFaVbgD9UrOdeTDHkN09S',
    'Ирина Соколова',
    'TEAM_LEAD'
);

-- MANAGER 1
INSERT INTO public.users (id, tenant_id, email, password_hash, full_name, role) VALUES (
    '00000000-0000-0000-0000-000000000012',
    '00000000-0000-0000-0000-000000000001',
    'manager1@dev-clinic.ru',
    '$2a$12$xZABa2FS44DMwAWuyuvQZuL0h7ycFzXtAFaVbgD9UrOdeTDHkN09S',
    'Ольга Иванова',
    'MANAGER'
);

-- MANAGER 2
INSERT INTO public.users (id, tenant_id, email, password_hash, full_name, role) VALUES (
    '00000000-0000-0000-0000-000000000013',
    '00000000-0000-0000-0000-000000000001',
    'manager2@dev-clinic.ru',
    '$2a$12$xZABa2FS44DMwAWuyuvQZuL0h7ycFzXtAFaVbgD9UrOdeTDHkN09S',
    'Дмитрий Петров',
    'MANAGER'
);

-- -------------------------------------------
-- Структура тенанта (departments + managers)
-- -------------------------------------------
SET search_path = tenant_dev_clinic, public;

INSERT INTO departments (id, name, description) VALUES
    ('00000000-0000-0000-0001-000000000001', 'Отдел продаж А', 'Первичные обращения'),
    ('00000000-0000-0000-0001-000000000002', 'Отдел продаж Б', 'Повторные обращения');

INSERT INTO managers (id, user_id, department_id, extension) VALUES
    ('00000000-0000-0000-0002-000000000001',
     '00000000-0000-0000-0000-000000000012',
     '00000000-0000-0000-0001-000000000001',
     '101'),
    ('00000000-0000-0000-0002-000000000002',
     '00000000-0000-0000-0000-000000000013',
     '00000000-0000-0000-0001-000000000002',
     '102');

-- -------------------------------------------
-- Dev скрипт оценки (мигрируем template_a.md)
-- -------------------------------------------
INSERT INTO scripts (id, name, call_type) VALUES (
    '00000000-0000-0000-0003-000000000001',
    'Запись на приём',
    'запись_на_прием'
);

-- Первые 5 критериев из template_a.md (остальные добавляются через UI)
INSERT INTO criteria (script_id, order_num, name, description, group_type, weight) VALUES
    ('00000000-0000-0000-0003-000000000001', 1,
     'Приветствие',
     'Менеджер поздоровался и назвал название клиники',
     'required', 1.0),
    ('00000000-0000-0000-0003-000000000001', 2,
     'Представление',
     'Менеджер назвал своё имя',
     'required', 1.0),
    ('00000000-0000-0000-0003-000000000001', 3,
     'Выявление потребности',
     'Менеджер уточнил цель звонка и пожелания пациента',
     'required', 1.5),
    ('00000000-0000-0000-0003-000000000001', 4,
     'Предложение времени',
     'Менеджер предложил конкретное время записи',
     'required', 1.0),
    ('00000000-0000-0000-0003-000000000001', 5,
     'Подтверждение записи',
     'Менеджер подтвердил дату, время и специалиста',
     'required', 2.0);

-- Сбрасываем search_path
RESET search_path;

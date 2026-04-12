-- =============================================================================
-- V30: Актуализация справочника сотрудников
--   • Новые отделы: АУП, ОП
--   • Новый сотрудник: Ковалёва Ксения Александровна
--   • Назначение отделов для тех, у кого department_id = NULL
--   • Исправление неверных отделов (Рогачёв → ОИИ гидрологи, Карпов → ОИИ геодезия,
--     Непомнящая → ОЭП атмосферный воздух)
--   • Добавление мобильных номеров телефонов
-- =============================================================================
SET search_path = tenant_sib_standart, public;

-- Dept UUIDs from V15 (for reference):
--   Бухгалтерия              45e57a87-5682-547e-b05e-d16cd8bf7a99
--   Орган инспекции №1       7135d203-737f-56c3-b10f-a94d081f93a7
--   ОРКК                     068afa12-8bbb-55e1-a5b7-9f11f9ccf401
--   ОИИ экологи              cacdbfa5-ad51-5072-b7de-4199cb959b53
--   ОИИ гидрологи            2fe91a66-a592-5a78-8837-50f7eb927cb9
--   ОИИ геодезия             ffa0ccf3-05e2-5f6b-9eab-499adae01552
--   МОС                      d71a9628-c4d5-5898-88f1-c3964f3c616c
--   ОЭП ОВОС (ООС)           b1113ceb-bcc5-5f46-ab6b-ae8f4be9c005
--   ОЭП атм. воздух          61771f42-d811-5841-8c24-10c1e31fec5f
--   Лаб Производственная     8293d274-b17f-50ee-a49f-df22a95f658c
--   Лаб Группа контроля      1d3e705c-ebb7-5b30-b83d-f8e0567a66be
--   Лаб по оформлению        866bc160-b4fd-5b9a-b5f2-6e7af60172cb
--   Лаб приборных            ac70c33d-ea4d-55c3-a8ad-c5ebf7c9a347
--   Лаб ГВР                  dc21ff6e-4c60-589f-87a6-2294c1c3cf5a
--   Транспортный             8b9152a1-113e-51b2-a795-f2d0361a2aba

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Новые отделы
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO departments (id, name, description, created_at) VALUES
    ('c1a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c', 'АУП',
     'Административно-управленческий персонал', EXTRACT(EPOCH FROM NOW())::BIGINT * 1000),
    ('d2b3c4d5-e6f7-4a8b-9c0d-1e2f3a4b5c6d', 'ОП',
     'Отдел продаж', EXTRACT(EPOCH FROM NOW())::BIGINT * 1000)
ON CONFLICT DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Новый сотрудник: Ковалёва Ксения Александровна
--    (не было в V15; Лаб Группа контроля, ext 1484, phone 79086609166)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO public.users (id, tenant_id, email, password_hash, full_name, role, created_at, updated_at)
VALUES (
    'e3c4d5e6-f7a8-4b9c-0d1e-2f3a4b5c6d7e',
    '00000000-0000-0000-0000-000000000001',
    'kovalyova.k@malikov.ru',
    '$2a$12$xZABa2FS44DMwAWuyuvQZuL0h7ycFzXtAFaVbgD9UrOdeTDHkN09S',
    'Ковалёва Ксения Александровна',
    'MANAGER',
    EXTRACT(EPOCH FROM NOW())::BIGINT * 1000,
    EXTRACT(EPOCH FROM NOW())::BIGINT * 1000
) ON CONFLICT DO NOTHING;

INSERT INTO managers (id, user_id, department_id, extension, phone_number, is_active, created_at, updated_at)
VALUES (
    'f4d5e6f7-a8b9-4c0d-1e2f-3a4b5c6d7e8f',
    'e3c4d5e6-f7a8-4b9c-0d1e-2f3a4b5c6d7e',
    '1d3e705c-ebb7-5b30-b83d-f8e0567a66be',  -- Лаб Группа контроля
    '1484',
    '79086609166',
    TRUE,
    EXTRACT(EPOCH FROM NOW())::BIGINT * 1000,
    EXTRACT(EPOCH FROM NOW())::BIGINT * 1000
) ON CONFLICT DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. Массовое обновление через прямые UPDATE по user_id (из V15 — фиксированные)
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
DECLARE
    v_now BIGINT := EXTRACT(EPOCH FROM NOW())::BIGINT * 1000;

    -- Новые отделы (вставлены выше)
    c_dept_aup  CONSTANT UUID := 'c1a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c';
    c_dept_op   CONSTANT UUID := 'd2b3c4d5-e6f7-4a8b-9c0d-1e2f3a4b5c6d';

    -- Существующие отделы из V15
    c_dept_buh      CONSTANT UUID := '45e57a87-5682-547e-b05e-d16cd8bf7a99';
    c_dept_oi1      CONSTANT UUID := '7135d203-737f-56c3-b10f-a94d081f93a7';
    c_dept_orkk     CONSTANT UUID := '068afa12-8bbb-55e1-a5b7-9f11f9ccf401';
    c_dept_oii_eco  CONSTANT UUID := 'cacdbfa5-ad51-5072-b7de-4199cb959b53';
    c_dept_oii_hyd  CONSTANT UUID := '2fe91a66-a592-5a78-8837-50f7eb927cb9';
    c_dept_oii_geo  CONSTANT UUID := 'ffa0ccf3-05e2-5f6b-9eab-499adae01552';
    c_dept_mos      CONSTANT UUID := 'd71a9628-c4d5-5898-88f1-c3964f3c616c';
    c_dept_oep_oos  CONSTANT UUID := 'b1113ceb-bcc5-5f46-ab6b-ae8f4be9c005';
    c_dept_oep_atm  CONSTANT UUID := '61771f42-d811-5841-8c24-10c1e31fec5f';
    c_dept_lab_prod CONSTANT UUID := '8293d274-b17f-50ee-a49f-df22a95f658c';
    c_dept_lab_gvr  CONSTANT UUID := 'dc21ff6e-4c60-589f-87a6-2294c1c3cf5a';
BEGIN

    -- ═══════════════════════════════════════════════════════════════
    -- АУП (были без отдела)
    -- ═══════════════════════════════════════════════════════════════
    -- Маликов Максим Анатольевич (user 468cabd3, ext 1630)
    UPDATE managers SET department_id = c_dept_aup, updated_at = v_now
        WHERE user_id = '468cabd3-231a-5f75-9161-188d2d50d636';
    -- Мясникова Татьяна Викторовна (ext 1631)
    UPDATE managers SET department_id = c_dept_aup, updated_at = v_now
        WHERE user_id = '5cf531e5-b74f-5702-adf5-5708d436bb47';
    -- Волков Денис Евгеньевич (ext 1633)
    UPDATE managers SET department_id = c_dept_aup, updated_at = v_now
        WHERE user_id = '1669c8f2-e06b-5d01-b8fb-fec6abb13d85';
    -- Важенина Надежда Ильинична (ext 1730)
    UPDATE managers SET department_id = c_dept_aup, updated_at = v_now
        WHERE user_id = '713d25dc-4959-5ee0-834e-a5795a8bff12';
    -- Минаев Александр Викторович (ext 1632)
    UPDATE managers SET department_id = c_dept_aup, updated_at = v_now
        WHERE user_id = '6de545ef-6893-58b8-8638-33db48074d34';
    -- Хангаев Андрей Валентинович (ext 1663, phone 79526375129)
    UPDATE managers SET department_id = c_dept_aup, phone_number = '79526375129', updated_at = v_now
        WHERE user_id = '625d646d-ca31-58d5-9c21-8b227a531471';
    -- Кравченко Оксана Михайловна (ext 1636, phone 79500859490)
    UPDATE managers SET department_id = c_dept_aup, phone_number = '79500859490', updated_at = v_now
        WHERE user_id = '49eb7a4e-deff-5512-93bb-795abda819fc';
    -- Шемякина Ксения Сергеевна (ext 1483, phone 79041239771)
    UPDATE managers SET department_id = c_dept_aup, phone_number = '79041239771', updated_at = v_now
        WHERE user_id = 'afb963d5-903e-5475-83b7-9364ea181639';
    -- Сергеева Елена Михайловна (ext 1637, phone 79016645234)
    UPDATE managers SET department_id = c_dept_aup, phone_number = '79016645234', updated_at = v_now
        WHERE user_id = '51fdc64c-fb87-5303-80a6-65b884ba298f';
    -- Андреева Ирина Владиславовна (ext 1640, phone 79016406949)
    UPDATE managers SET department_id = c_dept_aup, phone_number = '79016406949', updated_at = v_now
        WHERE user_id = 'bf772b9d-885f-53c1-84ea-f5638dffbcc0';
    -- Комисарова Татьяна Васильевна (ext 1638)
    UPDATE managers SET department_id = c_dept_aup, updated_at = v_now
        WHERE user_id = '2e42830e-0d53-5c4f-bff8-bb3dad3c4e7c';

    -- ═══════════════════════════════════════════════════════════════
    -- Бухгалтерия — добавить телефон Файзулиной, назначить Буданову
    -- ═══════════════════════════════════════════════════════════════
    -- Файзулина Гульсира Мунировна (dept уже Бухгалтерия, phone 79027666821)
    UPDATE managers SET phone_number = '79027666821', updated_at = v_now
        WHERE user_id = '0c98f67d-0f93-50d4-9242-6224a1ce24d9';
    -- Буданова Анастасия Александровна (ext 1635, dept был NULL)
    UPDATE managers SET department_id = c_dept_buh, updated_at = v_now
        WHERE user_id = '6c88e3ec-ef52-5bd1-852b-5ffaf0948492';

    -- ═══════════════════════════════════════════════════════════════
    -- ОП — назначить отдел и телефоны (dept был NULL)
    -- ═══════════════════════════════════════════════════════════════
    -- Банщикова Елена Леонидовна (ext 1729, phone 79526256143)
    UPDATE managers SET department_id = c_dept_op, phone_number = '79526256143', updated_at = v_now
        WHERE user_id = '8d372e65-a9cb-5715-afee-0f82acbe0c34';
    -- Гончарук Дарья Радиевна (ext 1644, phone 79500639766)
    UPDATE managers SET department_id = c_dept_op, phone_number = '79500639766', updated_at = v_now
        WHERE user_id = '9617c860-2e65-5b69-b41b-b74ee375ae53';
    -- Филиппова Татьяна Петровна (ext 1649, phone 79500786241)
    UPDATE managers SET department_id = c_dept_op, phone_number = '79500786241', updated_at = v_now
        WHERE user_id = '080cbee5-25c0-5ca2-81b5-868da189bc17';
    -- Медведев Дмитрий Александрович (ext 1646, phone 79041211290)
    UPDATE managers SET department_id = c_dept_op, phone_number = '79041211290', updated_at = v_now
        WHERE user_id = '76ec93d0-0f78-5718-a31c-6265cd0d0fba';
    -- Бархутова Валерия Вениаминовна (ext 1647, phone 79016720841)
    UPDATE managers SET department_id = c_dept_op, phone_number = '79016720841', updated_at = v_now
        WHERE user_id = '4a8d472c-fa9b-501a-85f1-22536e3bd469';
    -- Урбаева Елена Сергеевна (ext 1648, phone 79500953079)
    UPDATE managers SET department_id = c_dept_op, phone_number = '79500953079', updated_at = v_now
        WHERE user_id = '8db3be5a-8862-5d6d-97a3-15507c0151fc';

    -- ═══════════════════════════════════════════════════════════════
    -- ОРКК — телефон Турушева + Карцевой (dept уже ОРКК);
    --         назначить Ржаных, Тюменцеву, Батыреву (dept был NULL)
    -- ═══════════════════════════════════════════════════════════════
    -- Турушев Максим Евгеньевич (phone 79501317922)
    UPDATE managers SET phone_number = '79501317922', updated_at = v_now
        WHERE user_id = '90ce49be-e9cc-5cc2-9c82-778acb116c58';
    -- Карцева Анастасия Александровна (phone 79025167655)
    UPDATE managers SET phone_number = '79025167655', updated_at = v_now
        WHERE user_id = '410fef8c-1746-566b-b339-8a3b75fcc4ba';
    -- Ржаных Ольга Николаевна (ext 1726, phone 79501102658)
    UPDATE managers SET department_id = c_dept_orkk, phone_number = '79501102658', updated_at = v_now
        WHERE user_id = '40cc7429-43ca-5800-b71b-0cfb5b8925b1';
    -- Тюменцева Маргарита Васильевна (ext 1727, phone 79027662813)
    UPDATE managers SET department_id = c_dept_orkk, phone_number = '79027662813', updated_at = v_now
        WHERE user_id = '8b08f5ec-d5cc-5704-9b88-b0da5ea3757c';
    -- Батырева Валентина Алексеевна (ext 1643, phone 79526256156)
    UPDATE managers SET department_id = c_dept_orkk, phone_number = '79526256156', updated_at = v_now
        WHERE user_id = 'd1cd8b3f-3e14-5feb-bd8a-5467c0929118';

    -- ═══════════════════════════════════════════════════════════════
    -- ОИИ экологи — назначить Захарову и Веселовскую (dept был NULL)
    -- ═══════════════════════════════════════════════════════════════
    -- Захарова Евгения Дмитриевна (ext 1651, phone 79500690011)
    UPDATE managers SET department_id = c_dept_oii_eco, phone_number = '79500690011', updated_at = v_now
        WHERE user_id = '264f6cf9-0ad0-5095-a220-95d1d1dd868c';
    -- Веселовская Ольга Андреевна (ext 1656, phone 79027656859)
    UPDATE managers SET department_id = c_dept_oii_eco, phone_number = '79027656859', updated_at = v_now
        WHERE user_id = 'dfa68ad2-2815-5002-8d74-09e970fe96e9';

    -- ═══════════════════════════════════════════════════════════════
    -- ОИИ гидрологи / геодезия — ИСПРАВЛЕНИЕ:
    --   V15 назначил Рогачёва и Карпова в "ОИИ гидрометеорология и геодезия"
    --   Теперь: Рогачёв → ОИИ гидрологи (2fe91a66)
    --            Карпов  → ОИИ геодезия (ffa0ccf3)
    -- ═══════════════════════════════════════════════════════════════
    -- Рогачёв Аркадий Петрович (ext 1486, phone 79016720889) → ОИИ гидрологи
    UPDATE managers SET department_id = c_dept_oii_hyd, phone_number = '79016720889', updated_at = v_now
        WHERE user_id = '0d4eeb7f-8fa1-59bf-a7a2-63d459c2bc82';
    -- Карпов Кирилл Прокопьевич (phone 79016741241) → ОИИ геодезия
    UPDATE managers SET department_id = c_dept_oii_geo, phone_number = '79016741241', updated_at = v_now
        WHERE user_id = '8c5be9fa-84a5-52dc-8a1a-6530a336f7ab';

    -- Бузанакова Ирина Артуровна — телефон (dept ОИИ геодезия уже ✓)
    UPDATE managers SET phone_number = '79501318014', updated_at = v_now
        WHERE user_id = '2b60fd61-13fa-581a-aa57-8c06e30773e2';

    -- ═══════════════════════════════════════════════════════════════
    -- МОС — назначить Чернова (dept был NULL)
    -- ═══════════════════════════════════════════════════════════════
    -- Чернов Никита Алексеевич (ext 1653, phone 79016741233)
    UPDATE managers SET department_id = c_dept_mos, phone_number = '79016741233', updated_at = v_now
        WHERE user_id = '0ee8d8df-d52c-5a74-a291-de88f34eb17e';

    -- ═══════════════════════════════════════════════════════════════
    -- ОЭП — назначить Манькова и Ковалёва;
    --        ИСПРАВИТЬ Непомнящую (b1113ceb → 61771f42);
    --        добавить телефоны
    -- ═══════════════════════════════════════════════════════════════
    -- Маньков Максим Петрович (ext 1652, phone 79025167691) → ОЭП ОВОС
    UPDATE managers SET department_id = c_dept_oep_oos, phone_number = '79025167691', updated_at = v_now
        WHERE user_id = '32cff59a-4172-5aed-8a56-8c658fbdc5d4';
    -- Ковалёв Антон Александрович (ext 1655, phone 79500680099) → ОЭП ОВОС
    UPDATE managers SET department_id = c_dept_oep_oos, phone_number = '79500680099', updated_at = v_now
        WHERE user_id = '7732500c-ddbf-5c9b-befa-f6e514a4c7ec';

    -- Непомнящая Наталия Николаевна — ИСПРАВИТЬ на ОЭП атм. воздух + phone
    UPDATE managers SET department_id = c_dept_oep_atm, phone_number = '79016741346', updated_at = v_now
        WHERE user_id = 'fda31a58-632f-5a63-908a-c2859e18516e';

    -- Шелест Кристина Юрьевна (phone 79500874320, dept ОЭП ОВОС уже ✓)
    UPDATE managers SET phone_number = '79500874320', updated_at = v_now
        WHERE user_id = '5ebe52b6-b2bb-57ec-9a41-0a1e7a72dd70';
    -- Логинова Ирина Андреевна (phone 79500874423)
    UPDATE managers SET phone_number = '79500874423', updated_at = v_now
        WHERE user_id = '46e46dfd-e84f-5201-8038-0213bfb44bc4';
    -- Мельникова Мария Игоревна (phone 79016645211)
    UPDATE managers SET phone_number = '79016645211', updated_at = v_now
        WHERE user_id = '3e434e12-bc50-5846-9556-9f459d3ada6f';
    -- Баранова Дарья Андреевна (phone 79087725898, dept ОЭП природопользование ≈ ОЭП ОВОС ✓)
    UPDATE managers SET phone_number = '79087725898', updated_at = v_now
        WHERE user_id = '90e3826f-dcc7-5981-b9c0-740f77746cb6';
    -- Батодалаева Дари Александровна (phone 79086545226)
    UPDATE managers SET phone_number = '79086545226', updated_at = v_now
        WHERE user_id = 'f14edd90-ddbe-5217-abad-1ca2baf2cbdf';
    -- Шарифулина Розалия Рамильевна (phone 79500876815, dept ОЭП атм ✓)
    UPDATE managers SET phone_number = '79500876815', updated_at = v_now
        WHERE user_id = '6895e208-e758-5c63-b116-83e5505f730b';

    -- ═══════════════════════════════════════════════════════════════
    -- Лаборатория (общие — назначить в Лаб Производственная)
    -- ═══════════════════════════════════════════════════════════════
    -- Пирогова Дарья Эдвардовна (ext 1661, phone 79500849490)
    UPDATE managers SET department_id = c_dept_lab_prod, phone_number = '79500849490', updated_at = v_now
        WHERE user_id = '4398911c-b6a6-5210-b0a2-a6987e2f5aab';
    -- Гузеева Валентина Сергеевна (ext 1645, primary phone 79016746484; второй номер 79086609121 не хранится)
    UPDATE managers SET department_id = c_dept_lab_prod, phone_number = '79016746484', updated_at = v_now
        WHERE user_id = 'f1119db4-d1fb-54d3-9e27-d2dc441dfe4f';
    -- Монзоева Любовь Олеговна (ext 1728)
    UPDATE managers SET department_id = c_dept_lab_prod, updated_at = v_now
        WHERE user_id = 'a14bd752-d6d8-5d59-ac19-9c4b2dcb3b72';
    -- Баранова Валерия Максимовна (ext 1724)
    UPDATE managers SET department_id = c_dept_lab_prod, updated_at = v_now
        WHERE user_id = 'fca9fd9c-9437-5bc3-b964-5eea55025863';

    -- Александрова Анастасия Викторовна (phone 79086588141, dept Лаб приборных ✓)
    UPDATE managers SET phone_number = '79086588141', updated_at = v_now
        WHERE user_id = '050f35d2-73e5-5178-9d77-4684dc3ca8b9';

    -- Даликатная Анастасия Вадимовна (phone 79016690862, dept Лаб по оформлению ✓)
    UPDATE managers SET phone_number = '79016690862', updated_at = v_now
        WHERE user_id = 'bcd679e7-be0d-5c93-b8b8-28d8e7419244';

    -- ═══════════════════════════════════════════════════════════════
    -- Лаборатория ГВР — назначить Скрыпаль + телефоны всей группы
    -- ═══════════════════════════════════════════════════════════════
    -- Скрыпаль Любовь Владимировна (ext 1722, phone 79016746470; dept был NULL)
    UPDATE managers SET department_id = c_dept_lab_gvr, phone_number = '79016746470', updated_at = v_now
        WHERE user_id = '848f77e0-40f6-5878-b43c-5ba42619e040';
    -- Гуляшинов Евгений Николаевич (ext 1487, phone 79016720881, dept ГВР ✓)
    UPDATE managers SET phone_number = '79016720881', updated_at = v_now
        WHERE user_id = 'e8f83fd0-a940-5872-aecc-86481795b36c';
    -- Барнаков Максим Максимович (phone 79016301733, dept ГВР ✓)
    UPDATE managers SET phone_number = '79016301733', updated_at = v_now
        WHERE user_id = 'b8aa776d-7577-5598-aa19-d72d031c6979';
    -- Хлыстов Виктор Сергеевич (phone 79500876685, dept ГВР ✓)
    UPDATE managers SET phone_number = '79500876685', updated_at = v_now
        WHERE user_id = '4ed5194f-b100-59c2-8333-788a5adff685';
    -- Вяткин Артем Николаевич (phone 79500876832, dept ГВР ✓)
    UPDATE managers SET phone_number = '79500876832', updated_at = v_now
        WHERE user_id = '9e23dc3e-3b6f-529d-8b75-b21112d4d670';
    -- Назаренко Елизавета Петровна (phone 79500876771, dept ГВР ✓)
    UPDATE managers SET phone_number = '79500876771', updated_at = v_now
        WHERE user_id = '2bac1bb0-9af8-540c-8c33-90cce7d92cf9';
    -- Семигузов Назар Денисович (phone 79086406483, dept ГВР ✓)
    UPDATE managers SET phone_number = '79086406483', updated_at = v_now
        WHERE user_id = 'b37cafeb-e266-5526-849c-c11b8f64abdf';
    -- Рубцов Михаил Дмитриевич (phone 79500876751, dept ГВР ✓)
    UPDATE managers SET phone_number = '79500876751', updated_at = v_now
        WHERE user_id = 'fd10fa4e-3e78-5141-927a-9cb040985879';
    -- Полывянный Александр Максимович (phone 79501102548, dept ГВР ✓)
    UPDATE managers SET phone_number = '79501102548', updated_at = v_now
        WHERE user_id = '34c6e7df-c290-5f55-9730-c3a13b0f971c';

    -- ═══════════════════════════════════════════════════════════════
    -- Транспортный — телефон Константинова (dept уже ✓)
    -- ═══════════════════════════════════════════════════════════════
    -- Константинов Андрей Владимирович (ext 1639, phone 79016720847)
    UPDATE managers SET phone_number = '79016720847', updated_at = v_now
        WHERE user_id = 'cebe6e2e-21f1-5e35-b0db-3789cf053db1';

    RAISE NOTICE 'V30: Employee directory updated successfully';
END $$;

RESET search_path;

-- =============================================================================
-- V1: Public schema — глобальные таблицы (тенанты, пользователи, тарифы)
-- =============================================================================

-- -------------------------------------------
-- TENANTS
-- -------------------------------------------
CREATE TABLE public.tenants (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    slug        TEXT        NOT NULL UNIQUE,          -- "clinic_alpha"
    name        TEXT        NOT NULL,                 -- "Клиника Альфа"
    db_schema   TEXT        NOT NULL UNIQUE,          -- "tenant_clinic_alpha"
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenants_slug ON public.tenants (slug);

-- -------------------------------------------
-- PLANS (тарифные планы)
-- -------------------------------------------
CREATE TABLE public.plans (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name                TEXT        NOT NULL,          -- "Starter", "Business", "Enterprise"
    minutes_limit       INT         NOT NULL,          -- лимит минут в месяц (-1 = безлимит)
    price_per_minute    NUMERIC(10,4) NOT NULL DEFAULT 0,
    is_active           BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Дефолтные тарифы
INSERT INTO public.plans (name, minutes_limit, price_per_minute) VALUES
    ('Starter',    500,   0.0200),
    ('Business',   2000,  0.0150),
    ('Enterprise', -1,    0.0100);

-- -------------------------------------------
-- TENANT SUBSCRIPTIONS (биллинг)
-- -------------------------------------------
CREATE TABLE public.tenant_subscriptions (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    plan_id         UUID        NOT NULL REFERENCES public.plans(id),
    minutes_used    INT         NOT NULL DEFAULT 0,
    period_start    DATE        NOT NULL,
    period_end      DATE        NOT NULL,
    is_current      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_period CHECK (period_end > period_start)
);

CREATE INDEX idx_tenant_subs_tenant ON public.tenant_subscriptions (tenant_id, is_current);

-- -------------------------------------------
-- USERS
-- -------------------------------------------
CREATE TYPE public.user_role AS ENUM (
    'MANAGER',
    'TEAM_LEAD',
    'CLIENT_ADMIN',
    'SUPERADMIN'
);

CREATE TABLE public.users (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        REFERENCES public.tenants(id) ON DELETE CASCADE,  -- NULL для SUPERADMIN
    email           TEXT        NOT NULL UNIQUE,
    password_hash   TEXT        NOT NULL,
    full_name       TEXT        NOT NULL,
    role            public.user_role NOT NULL,
    is_active       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- SUPERADMIN не привязан к тенанту, остальные обязаны
    CONSTRAINT chk_tenant_role CHECK (
        (role = 'SUPERADMIN' AND tenant_id IS NULL) OR
        (role != 'SUPERADMIN' AND tenant_id IS NOT NULL)
    )
);

CREATE INDEX idx_users_tenant    ON public.users (tenant_id);
CREATE INDEX idx_users_email     ON public.users (email);
CREATE INDEX idx_users_role      ON public.users (role);

-- -------------------------------------------
-- REFRESH TOKENS
-- -------------------------------------------
CREATE TABLE public.refresh_tokens (
    token_hash      TEXT        PRIMARY KEY,           -- SHA-256 токена
    user_id         UUID        NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    device_info     TEXT,                              -- "Chrome / macOS"
    ip_address      TEXT,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at      TIMESTAMPTZ                        -- NULL = активен
);

CREATE INDEX idx_refresh_user    ON public.refresh_tokens (user_id);
CREATE INDEX idx_refresh_expires ON public.refresh_tokens (expires_at) WHERE revoked_at IS NULL;

-- -------------------------------------------
-- SUPERADMIN по умолчанию (пароль меняется при первом входе)
-- -------------------------------------------
INSERT INTO public.users (email, password_hash, full_name, role, tenant_id)
VALUES (
    'admin@malikov.ai',
    -- bcrypt hash для 'changeme123' — ОБЯЗАТЕЛЬНО сменить при деплое
    '$2a$12$xZABa2FS44DMwAWuyuvQZuL0h7ycFzXtAFaVbgD9UrOdeTDHkN09S',
    'Super Admin',
    'SUPERADMIN',
    NULL
);

#!/usr/bin/env bash
set -euo pipefail

DB_APP_PASSWORD="${DB_APP_PASSWORD:-malikov_dev}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE EXTENSION IF NOT EXISTS "pgcrypto";
    CREATE EXTENSION IF NOT EXISTS "pg_trgm";

    DO \$\$
    BEGIN
      IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'malikov_app') THEN
        CREATE ROLE malikov_app LOGIN PASSWORD '${DB_APP_PASSWORD}';
      END IF;
    END
    \$\$;

    GRANT ALL PRIVILEGES ON DATABASE ${POSTGRES_DB} TO malikov_app;
    GRANT ALL ON SCHEMA public TO malikov_app;
EOSQL

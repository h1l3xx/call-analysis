-- Legacy file, replaced by init-db.sh
-- Kept for reference only
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'malikov_app') THEN
    CREATE ROLE malikov_app LOGIN PASSWORD 'malikov_dev';
  END IF;
END
$$;

GRANT ALL PRIVILEGES ON DATABASE malikov TO malikov_app;
GRANT ALL ON SCHEMA public TO malikov_app;

#!/usr/bin/env bash
set -euo pipefail

DB_CONTAINER="${DB_CONTAINER:-malikov_postgres}"
DB_USER="${DB_USER:-malikov}"
DB_NAME="${DB_NAME:-malikov}"
OUTPUT_FILE="/opt/malikov/credentials.txt"

psql_cmd() {
  docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAc "$1"
}

docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -c \
  "CREATE EXTENSION IF NOT EXISTS pgcrypto;" >/dev/null 2>&1

emails=$(psql_cmd "SELECT email FROM public.users WHERE role = 'MANAGER' ORDER BY full_name;")

if [ -z "$emails" ]; then
  echo "No MANAGER users found."
  exit 1
fi

> "$OUTPUT_FILE"
chmod 600 "$OUTPUT_FILE"

count=0
while IFS= read -r email; do
  [ -z "$email" ] && continue
  password=$(openssl rand -base64 12 | tr -dc 'A-Za-z0-9' | head -c 12)
  psql_cmd "UPDATE public.users SET password_hash = crypt('${password}', gen_salt('bf', 12)) WHERE email = '${email}';" >/dev/null
  echo "${email}  ${password}" >> "$OUTPUT_FILE"
  count=$((count + 1))
done <<< "$emails"

echo "Done: ${count} passwords reset."
echo "Credentials saved to: ${OUTPUT_FILE}"
echo "IMPORTANT: transfer this file securely and delete from server after use."

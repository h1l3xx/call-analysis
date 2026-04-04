#!/usr/bin/env bash
set -euo pipefail

DB_CONTAINER="${DB_CONTAINER:-malikov_postgres}"
DB_USER="${DB_USER:-malikov}"
DB_NAME="${DB_NAME:-malikov}"
PY_CONTAINER="${PY_CONTAINER:-malikov_pipeline}"
OUTPUT_FILE="/opt/malikov/credentials.txt"

psql_cmd() {
  docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAc "$1"
}

bcrypt_hash() {
  docker exec "$PY_CONTAINER" python3 -c "
import bcrypt
h = bcrypt.hashpw(b'$1', bcrypt.gensalt(12)).decode().replace('\$2b\$', '\$2a\$')
print(h)
"
}

echo "Installing bcrypt in pipeline container..."
docker exec "$PY_CONTAINER" uv pip install bcrypt --quiet 2>/dev/null \
  || docker exec "$PY_CONTAINER" pip3 install bcrypt --quiet 2>/dev/null

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
  hash=$(bcrypt_hash "$password")
  psql_cmd "UPDATE public.users SET password_hash = '${hash}' WHERE email = '${email}';" >/dev/null
  echo "${email}  ${password}" >> "$OUTPUT_FILE"
  count=$((count + 1))
  printf "\r  [%d] %s" "$count" "$email"
done <<< "$emails"

echo ""
echo "Done: ${count} passwords reset."
echo "Credentials saved to: ${OUTPUT_FILE}"
echo "IMPORTANT: transfer this file securely and delete from server after use."

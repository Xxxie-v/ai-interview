#!/usr/bin/env bash

set -euo pipefail

env_file="${1:-.env.production}"
migration_dir="${2:-docs/sql}"

if [[ ! -f "$env_file" ]]; then
  echo "Environment file not found: $env_file" >&2
  exit 1
fi

if [[ ! -d "$migration_dir" ]]; then
  echo "Migration directory not found: $migration_dir" >&2
  exit 1
fi

read_env() {
  local key="$1"
  local line
  local value
  line="$(grep -m 1 -E "^${key}=" "$env_file" || true)"
  if [[ -z "$line" ]]; then
    echo "Required environment variable is missing: $key" >&2
    exit 1
  fi
  value="${line#*=}"
  if [[ "$value" == \"*\" && "$value" == *\" ]]; then
    value="${value:1:${#value}-2}"
  elif [[ "$value" == \'*\' && "$value" == *\' ]]; then
    value="${value:1:${#value}-2}"
  fi
  printf '%s' "$value"
}

postgres_host="$(read_env POSTGRES_HOST)"
postgres_port="$(read_env POSTGRES_PORT)"
postgres_user="$(read_env POSTGRES_USER)"
postgres_database="$(read_env POSTGRES_DB)"
export PGPASSWORD="$(read_env POSTGRES_PASSWORD)"

shopt -s nullglob
migrations=("$migration_dir"/*.sql)
if (( ${#migrations[@]} == 0 )); then
  echo "No SQL migrations found in $migration_dir" >&2
  exit 1
fi

for migration in "${migrations[@]}"; do
  echo "Applying migration: $(basename "$migration")"
  psql \
    -v ON_ERROR_STOP=1 \
    -h "$postgres_host" \
    -p "$postgres_port" \
    -U "$postgres_user" \
    -d "$postgres_database" \
    -f "$migration"
done

echo "PostgreSQL migrations completed"

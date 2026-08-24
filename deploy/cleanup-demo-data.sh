#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly DEPLOY_DIR="${LEDGERPAY_DEPLOY_DIR:-/opt/ledgerpay}"
readonly COMPOSE_FILE="${DEPLOY_DIR}/compose.production.yaml"
readonly ENV_FILE="${DEPLOY_DIR}/.env"
readonly SQL_FILE="${SCRIPT_DIR}/sql/cleanup-demo-data.sql"

retention_days=7
execute_cleanup=false

usage() {
  cat <<'USAGE'
Usage: cleanup-demo-data.sh [--dry-run | --execute] [--retention-days DAYS]

The default is a dry run with a 7-day retention period. Execution refuses a
retention period shorter than 7 days.
USAGE
}

while (($# > 0)); do
  case "$1" in
    --dry-run)
      execute_cleanup=false
      shift
      ;;
    --execute)
      execute_cleanup=true
      shift
      ;;
    --retention-days)
      if (($# < 2)); then
        echo "--retention-days requires a value." >&2
        exit 2
      fi
      retention_days="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ ! "$retention_days" =~ ^[0-9]+$ ]] || ((retention_days < 7 || retention_days > 365)); then
  echo "Retention days must be an integer from 7 through 365." >&2
  exit 2
fi

for required_file in "$COMPOSE_FILE" "$ENV_FILE" "$SQL_FILE"; do
  if [[ ! -f "$required_file" ]]; then
    echo "Required file is missing: $required_file" >&2
    exit 1
  fi
done

mode_label="DRY RUN"
if [[ "$execute_cleanup" == true ]]; then
  mode_label="EXECUTE"
fi

echo "LedgerPay demo cleanup: mode=${mode_label}, retention_days=${retention_days}"

docker compose \
  --env-file "$ENV_FILE" \
  --file "$COMPOSE_FILE" \
  exec -T postgres \
  sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" exec psql --no-psqlrc --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 --set "retention_days=$1" --set "execute_cleanup=$2"' \
  sh "$retention_days" "$execute_cleanup" \
  < "$SQL_FILE"

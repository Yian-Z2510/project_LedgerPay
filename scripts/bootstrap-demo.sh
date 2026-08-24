#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
FRONTEND_ENV_FILE="${PROJECT_ROOT}/frontend/.env.local"
API_BASE_URL="${LEDGERPAY_API_BASE_URL:-http://localhost:8080}"
API_BASE_URL="${API_BASE_URL%/}"
WEBHOOK_URL="${LEDGERPAY_WEBHOOK_URL:-http://localhost:9000/webhook}"

RESPONSE_FILE="$(mktemp "${TMPDIR:-/tmp}/ledgerpay-merchant-response.XXXXXX")"
ENV_TEMP_FILE=""

cleanup() {
  rm -f "${RESPONSE_FILE}"
  if [[ -n "${ENV_TEMP_FILE}" ]]; then
    rm -f "${ENV_TEMP_FILE}"
  fi
}

trap cleanup EXIT

if ! command -v python3 >/dev/null 2>&1; then
  echo "Error: python3 is required to read the Merchant API response." >&2
  echo "The existing frontend/.env.local was not changed." >&2
  exit 1
fi

timestamp="$(date -u +%Y%m%d%H%M%S)"
demo_email="ledgerpay-demo-${timestamp}-$$@example.com"
request_body="$(python3 -c 'import json, sys
print(json.dumps({
    "name": "LedgerPay Demo",
    "email": sys.argv[1],
    "webhookUrl": sys.argv[2],
}))' "${demo_email}" "${WEBHOOK_URL}")"

echo "Creating a demo Merchant at ${API_BASE_URL}..."

if ! http_status="$(curl \
  --silent \
  --show-error \
  --output "${RESPONSE_FILE}" \
  --write-out '%{http_code}' \
  --request POST \
  --header 'Content-Type: application/json' \
  --data "${request_body}" \
  "${API_BASE_URL}/api/v1/merchants")"; then
  echo "Error: Could not connect to the LedgerPay backend at ${API_BASE_URL}." >&2
  echo "The existing frontend/.env.local was not changed." >&2
  exit 1
fi

if [[ "${http_status}" != "201" ]]; then
  error_message="$(python3 -c 'import json, sys
try:
    data = json.load(open(sys.argv[1], encoding="utf-8"))
    print(data.get("message") or data.get("code") or "unknown backend error")
except Exception:
    print("unknown backend error")' "${RESPONSE_FILE}")"
  echo "Error: Merchant creation returned HTTP ${http_status}: ${error_message}" >&2
  echo "The existing frontend/.env.local was not changed." >&2
  exit 1
fi

if ! api_key="$(python3 -c 'import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
key = data.get("apiKey")
if not isinstance(key, str) or not key:
    raise SystemExit(1)
print(key)' "${RESPONSE_FILE}" 2>/dev/null)"; then
  echo "Error: Merchant was created, but the response did not contain a valid API key." >&2
  echo "The existing frontend/.env.local was not changed." >&2
  exit 1
fi

ENV_TEMP_FILE="$(mktemp "${PROJECT_ROOT}/frontend/.env.local.XXXXXX")"
{
  printf 'VITE_API_TARGET=%s\n' "${API_BASE_URL}"
  printf 'LEDGERPAY_DEMO_API_KEY=%s\n' "${api_key}"
  printf 'LEDGERPAY_WEBHOOK_URL=%s\n' "${WEBHOOK_URL}"
} > "${ENV_TEMP_FILE}"
chmod 600 "${ENV_TEMP_FILE}"
mv "${ENV_TEMP_FILE}" "${FRONTEND_ENV_FILE}"
ENV_TEMP_FILE=""

masked_key="••••${api_key: -4}"

echo "Demo Merchant created (API key: ${masked_key})."
echo "Webhook URL configured: ${WEBHOOK_URL}"
echo "frontend/.env.local updated."
echo "If Vite is already running, restart npm run dev."

#!/usr/bin/env bash

set -uo pipefail
umask 077

readonly DEPLOY_DIR="${DEPLOY_DIR:-/opt/ledgerpay}"
readonly COMPOSE_FILE="${DEPLOY_DIR}/compose.production.yaml"
readonly ENV_FILE="${DEPLOY_DIR}/.env"
readonly HEALTH_URL="${HEALTH_URL:-https://ledgerpay.yianz.me/health}"
readonly STAGED_COMPOSE_FILE="${COMPOSE_FILE}.next-${NEW_IMAGE_TAG:-invalid}"
readonly -a APP_SERVICES=(backend frontend webhook-receiver)
readonly -a COMPOSE=(docker compose --env-file "$ENV_FILE" --file "$COMPOSE_FILE")

PREVIOUS_IMAGE_TAG=""
ROLLBACK_COMPOSE_FILE=""
ROLLBACK_REQUIRED=false
ROLLBACK_ATTEMPTED=false

read_image_tag() {
  sed -n 's/^IMAGE_TAG=//p' "$ENV_FILE" | tail -n 1
}

set_image_tag() {
  local image_tag="$1"
  local temporary_env

  temporary_env="$(mktemp "${ENV_FILE}.tmp.XXXXXX")" || return 1
  if ! awk -v image_tag="$image_tag" '
    BEGIN { updated = 0 }
    !updated && /^IMAGE_TAG=/ {
      print "IMAGE_TAG=" image_tag
      updated = 1
      next
    }
    { print }
    END { if (!updated) exit 1 }
  ' "$ENV_FILE" > "$temporary_env"; then
    rm -f -- "$temporary_env"
    return 1
  fi

  if ! chmod 600 "$temporary_env" || ! mv -f -- "$temporary_env" "$ENV_FILE"; then
    rm -f -- "$temporary_env"
    return 1
  fi
  [[ "$(read_image_tag)" == "$image_tag" ]]
}

verify_health() {
  local response

  for _ in {1..12}; do
    if response="$(curl --fail --silent --show-error --max-time 10 "$HEALTH_URL" 2>/dev/null)" \
      && grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' <<< "$response"; then
      return 0
    fi
    sleep 5
  done

  return 1
}

start_application_images() {
  "${COMPOSE[@]}" pull "${APP_SERVICES[@]}" || return 1
  "${COMPOSE[@]}" up --detach --wait --wait-timeout 180 "${APP_SERVICES[@]}" || return 1
  verify_health
}

preserve_and_activate_compose() {
  ROLLBACK_COMPOSE_FILE="$(mktemp "${COMPOSE_FILE}.rollback.XXXXXX")" || return 1

  if ! cp -p -- "$COMPOSE_FILE" "$ROLLBACK_COMPOSE_FILE" \
    || ! cmp -s "$COMPOSE_FILE" "$ROLLBACK_COMPOSE_FILE"; then
    rm -f -- "$ROLLBACK_COMPOSE_FILE"
    ROLLBACK_COMPOSE_FILE=""
    return 1
  fi

  if ! mv -f -- "$STAGED_COMPOSE_FILE" "$COMPOSE_FILE"; then
    rm -f -- "$ROLLBACK_COMPOSE_FILE"
    ROLLBACK_COMPOSE_FILE=""
    return 1
  fi

  ROLLBACK_REQUIRED=true
}

restore_previous_compose() {
  if [[ -z "$ROLLBACK_COMPOSE_FILE" || ! -f "$ROLLBACK_COMPOSE_FILE" ]]; then
    echo "The previous production Compose backup is unavailable." >&2
    return 1
  fi

  if ! mv -f -- "$ROLLBACK_COMPOSE_FILE" "$COMPOSE_FILE"; then
    echo "Failed to restore the previous production Compose file." >&2
    return 1
  fi

  ROLLBACK_COMPOSE_FILE=""
}

rollback_release() {
  local previous_image_tag="$1"
  local restore_failed=false

  restore_previous_compose || restore_failed=true
  if ! set_image_tag "$previous_image_tag"; then
    echo "Failed to restore the previous IMAGE_TAG." >&2
    restore_failed=true
  fi

  if [[ "$restore_failed" == true ]]; then
    return 1
  fi

  # A failed pull can still be recoverable when the previous images are cached.
  "${COMPOSE[@]}" pull "${APP_SERVICES[@]}" \
    || echo "Previous image pull failed; attempting rollback with cached images." >&2

  "${COMPOSE[@]}" up --detach --wait --wait-timeout 180 "${APP_SERVICES[@]}" \
    && verify_health
}

handle_exit() {
  local exit_status="$?"

  trap - EXIT HUP INT TERM

  if [[ "$exit_status" -ne 0 && "$ROLLBACK_REQUIRED" == true && "$ROLLBACK_ATTEMPTED" == false ]]; then
    ROLLBACK_ATTEMPTED=true
    echo "Deployment exited unexpectedly; restoring the previous Compose and application images." >&2
    if rollback_release "$PREVIOUS_IMAGE_TAG"; then
      echo "Emergency rollback health verification passed." >&2
    else
      echo "Emergency rollback failed; manual recovery is required." >&2
    fi
  fi

  if [[ "$ROLLBACK_REQUIRED" == false ]]; then
    rm -f -- "$STAGED_COMPOSE_FILE"
  fi

  exit "$exit_status"
}

trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
trap handle_exit EXIT

main() {
  if [[ ! -f "$COMPOSE_FILE" || ! -f "$ENV_FILE" || ! -f "$STAGED_COMPOSE_FILE" ]]; then
    echo "Production Compose, staged Compose, or .env is missing in ${DEPLOY_DIR}." >&2
    return 1
  fi

  if [[ ! "${NEW_IMAGE_TAG:-}" =~ ^[0-9a-f]{40}$ ]]; then
    echo "NEW_IMAGE_TAG must be a full Git commit SHA." >&2
    return 1
  fi

  if ! grep -q '^IMAGE_TAG=' "$ENV_FILE"; then
    echo "IMAGE_TAG is missing from the production .env." >&2
    return 1
  fi

  PREVIOUS_IMAGE_TAG="$(read_image_tag)"
  if [[ ! "$PREVIOUS_IMAGE_TAG" =~ ^[0-9a-f]{40}$ ]]; then
    echo "The current production IMAGE_TAG is not a full Git commit SHA." >&2
    return 1
  fi

  if ! docker compose --env-file "$ENV_FILE" --file "$STAGED_COMPOSE_FILE" config --quiet; then
    echo "The staged production Compose file is invalid." >&2
    return 1
  fi

  if ! preserve_and_activate_compose; then
    echo "Failed to preserve and activate the production Compose file." >&2
    return 1
  fi

  if ! set_image_tag "$NEW_IMAGE_TAG"; then
    echo "Failed to set the new IMAGE_TAG." >&2
    return 1
  fi

  if start_application_images; then
    ROLLBACK_REQUIRED=false
    if ! rm -f -- "$ROLLBACK_COMPOSE_FILE"; then
      echo "Production is healthy, but the temporary Compose backup could not be removed." >&2
    fi
    ROLLBACK_COMPOSE_FILE=""
    echo "Production health verification passed."
    return 0
  fi

  echo "Deployment health verification failed; rolling back Compose and application images." >&2
  ROLLBACK_ATTEMPTED=true
  if rollback_release "$PREVIOUS_IMAGE_TAG"; then
    echo "Rollback health verification passed; the deployment workflow will fail." >&2
  else
    echo "Rollback health verification failed; manual recovery is required." >&2
  fi
  ROLLBACK_REQUIRED=false

  return 1
}

main "$@"

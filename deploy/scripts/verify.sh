#!/usr/bin/env bash
# Post-deploy verification on the VPS. Read-only.
set -u
DIR=/opt/griip-internal-messaging
PROJECT=griip-internal-messaging
cd "$DIR" || exit 1
. ./.env
dc() { docker compose -p "$PROJECT" -f "$DIR/compose.yaml" "$@"; }
echo "== project"; dc ps
echo "== project resources (all must be prefixed ${PROJECT}_)"
docker network ls --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Name}}'
docker volume ls --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Name}}'
for i in $(seq 1 20); do
  st=$(docker inspect --format '{{.State.Health.Status}}' "$(dc ps -q api)" 2>/dev/null)
  [ "$st" = healthy ] && break; sleep 3
done
echo "api health: ${st:-unknown}"
echo "== API /healthz";   curl -fsS "http://127.0.0.1:${API_HOST_PORT:-18080}/healthz"; echo
echo "== LiveKit signal"; curl -fsS "http://127.0.0.1:${LIVEKIT_SIGNAL_HOST_PORT:-17880}/" ; echo
echo "== resource usage"; docker stats --no-stream $(dc ps -q)
echo "== restarts"; for c in $(dc ps -q); do docker inspect --format '{{.Name}} restarts={{.RestartCount}}' "$c"; done

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
echo "== disk (attachments are capped by ATTACHMENT_TOTAL_BYTES; warn below 5 GB free)"
free_kb=$(df -Pk /var/lib/docker 2>/dev/null | awk 'NR==2{print $4}')
echo "free: $((${free_kb:-0} / 1024 / 1024)) GB"; [ "${free_kb:-0}" -lt 5242880 ] && echo "WARNING: less than 5 GB free on the Docker disk"
echo "== restarts"; for c in $(dc ps -q); do docker inspect --format '{{.Name}} restarts={{.RestartCount}}' "$c"; done

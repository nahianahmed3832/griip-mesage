#!/usr/bin/env bash
# READ-ONLY preflight for the VPS. Changes nothing. Run before the first deploy:
#   bash scripts/preflight.sh
# Reports: resources, Docker/Compose versions, existing Compose projects, port conflicts,
# whether /opt/griip-internal-messaging already exists, and reverse-proxy candidates.
set -u
DIR=/opt/griip-internal-messaging
PROJECT=griip-internal-messaging
ENVF="$DIR/.env"; [ -f "$ENVF" ] && . "$ENVF"
PORTS_TCP="${API_HOST_PORT:-18080} ${LIVEKIT_SIGNAL_HOST_PORT:-17880} ${LIVEKIT_RTC_TCP_PORT:-7881}"
PORTS_UDP="${LIVEKIT_RTC_UDP_PORT:-7882}"
fail=0
echo "== host";      hostname; uname -srm; uptime
echo "== cpu/mem";   nproc; free -h 2>/dev/null || vm_stat
echo "== disk";      df -h / /opt 2>/dev/null | sort -u
echo "== docker";    docker --version && docker compose version || { echo "!! docker compose v2 missing"; fail=1; }
echo "== compose projects"; docker compose ls -a 2>/dev/null
if docker compose ls -a --format json 2>/dev/null | grep -q "\"Name\":\"$PROJECT\""; then echo "note: project $PROJECT already exists (redeploy)"; fi
echo "== containers"; docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
echo "== volumes named like ours"; docker volume ls --format '{{.Name}}' | grep -E "^${PROJECT}_" || echo "(none)"
echo "== directory"; if [ -e "$DIR" ]; then ls -la "$DIR"; else echo "$DIR does not exist (will be created)"; fi
echo "== port check"
for p in $PORTS_TCP; do
  if ss -Htln "sport = :$p" 2>/dev/null | grep -q .; then
    owner=$(docker ps --filter "publish=$p" --format '{{.Names}}' | head -1)
    if [ -n "$owner" ] && docker inspect "$owner" --format '{{index .Config.Labels "com.docker.compose.project"}}' | grep -qx "$PROJECT"; then echo "tcp/$p in use by our own $owner (ok)"; else echo "!! tcp/$p IN USE by ${owner:-a non-container process}"; fail=1; fi
  else echo "tcp/$p free"; fi
done
for p in $PORTS_UDP; do
  if ss -Hunl "sport = :$p" 2>/dev/null | grep -q .; then echo "!! udp/$p IN USE (check if ours)"; else echo "udp/$p free"; fi
done
echo "== reverse proxy candidates"
for s in nginx caddy traefik apache2 httpd; do pgrep -x "$s" >/dev/null 2>&1 && echo "running: $s"; done
docker ps --format '{{.Names}} {{.Image}}' | grep -Ei 'nginx|caddy|traefik|proxy' || true
echo "== firewall"; (ufw status 2>/dev/null || firewall-cmd --list-all 2>/dev/null || echo "no ufw/firewalld info") | head -30
[ $fail -eq 0 ] && echo "PREFLIGHT OK" || echo "PREFLIGHT FOUND CONFLICTS — resolve before deploying"
exit $fail

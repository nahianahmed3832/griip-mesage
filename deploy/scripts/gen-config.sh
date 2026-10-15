#!/usr/bin/env sh
# Create .env (if missing) with fresh LiveKit credentials and render livekit/livekit.yaml.
# Run from the deployment directory (/opt/griip-internal-messaging). Idempotent: never
# overwrites existing secrets.
set -eu
cd "$(dirname "$0")/.."
umask 077
if [ ! -f .env ]; then
  cp .env.example .env
  key="API$(head -c 9 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  secret="$(head -c 36 /dev/urandom | base64 | tr -d '/+=\n' | cut -c1-48)"
  sed -i.bak "s/^LIVEKIT_API_KEY=.*/LIVEKIT_API_KEY=${key}/; s/^LIVEKIT_API_SECRET=.*/LIVEKIT_API_SECRET=${secret}/" .env && rm -f .env.bak
  echo "Created .env with new LiveKit credentials. Edit PUBLIC_API_URL / LIVEKIT_PUBLIC_URL / ports."
fi
# shellcheck disable=SC1091
. ./.env
[ -n "${LIVEKIT_API_KEY:-}" ] && [ ${#LIVEKIT_API_SECRET} -ge 32 ] || { echo "LIVEKIT_API_KEY/SECRET missing or secret < 32 chars" >&2; exit 1; }
if [ -n "${LIVEKIT_NODE_IP:-}" ]; then node_line="node_ip: ${LIVEKIT_NODE_IP}"; use_ext=false; else node_line="# node_ip discovered via STUN"; use_ext="${LIVEKIT_USE_EXTERNAL_IP:-true}"; fi
sed -e "s/__RTC_TCP_PORT__/${LIVEKIT_RTC_TCP_PORT:-7881}/" \
    -e "s/__RTC_UDP_PORT__/${LIVEKIT_RTC_UDP_PORT:-7882}/" \
    -e "s/__USE_EXTERNAL_IP__/${use_ext}/" \
    -e "s|__NODE_IP_LINE__|${node_line}|" \
    -e "s/__LIVEKIT_API_KEY__/${LIVEKIT_API_KEY}/g" \
    -e "s/__LIVEKIT_API_SECRET__/${LIVEKIT_API_SECRET}/" \
    livekit/livekit.yaml.template > livekit/livekit.yaml
chmod 644 livekit/livekit.yaml   # read by the container user; directory itself should be 0750 root
echo "Rendered livekit/livekit.yaml"

#!/usr/bin/env bash
# Restore an archive made by scripts/backup.sh into this deployment. Run from the deployment directory:
#   bash scripts/restore.sh backups/griip-20261016T120000Z.tar.gz --yes
# Nothing is deleted: the current database moves to /data/pre-restore-<time>/ inside the volume, and
# restored attachment files are added next to the existing ones. The snapshot is checked first; if the
# check fails, the running deployment is left as it was.
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT=griip-internal-messaging
ARCHIVE="${1:?usage: restore.sh <archive.tar.gz> --yes}"
[ "${2:-}" = "--yes" ] || { echo "This replaces the live database (the current one is kept aside). Re-run with --yes to continue." >&2; exit 2; }
[ -f "$ARCHIVE" ] || { echo "no such archive: $ARCHIVE" >&2; exit 1; }
ARCHIVE="$(cd "$(dirname "$ARCHIVE")" && pwd)/$(basename "$ARCHIVE")"
snap=$(tar -tzf "$ARCHIVE" | grep -E '^backups/griip-[0-9TZ]+\.sqlite$' | head -1)
[ -n "$snap" ] || { echo "not a Griip Message backup archive (no backups/griip-*.sqlite inside)" >&2; exit 1; }
dc() { docker compose -p "$PROJECT" -f "$DIR/compose.yaml" "$@"; }
ts=$(date -u +%Y%m%dT%H%M%SZ)

echo "→ checking the snapshot (the server keeps running)"
dc run --rm --no-deps -T -v "$ARCHIVE:/restore/archive.tar.gz:ro" --entrypoint sh api -c "
  set -e
  mkdir -p /data/restore-$ts
  tar -C /data/restore-$ts -xzf /restore/archive.tar.gz
  node dist/admin.js verify-backup /data/restore-$ts/$snap || { rm -rf /data/restore-$ts; exit 1; }
"

echo "→ stopping the API"
dc stop api

echo "→ swapping in the snapshot (current database kept in /data/pre-restore-$ts)"
dc run --rm --no-deps -T --entrypoint sh api -c "
  set -e
  mkdir -p /data/pre-restore-$ts
  for f in /data/griip.sqlite /data/griip.sqlite-wal /data/griip.sqlite-shm; do [ -e \"\$f\" ] && mv \"\$f\" /data/pre-restore-$ts/; done
  mv /data/restore-$ts/$snap /data/griip.sqlite
  if [ -d /data/restore-$ts/attachments ]; then mkdir -p /data/attachments && cp -an /data/restore-$ts/attachments/. /data/attachments/; fi
  # The federation identity key is restored only where none exists: an existing identity is never replaced.
  if [ -d /data/restore-$ts/federation ] && [ ! -e /data/federation/server-key.json ]; then mkdir -p /data/federation && cp -a /data/restore-$ts/federation/. /data/federation/; fi
  rm -rf /data/restore-$ts
"

echo "→ starting the API (migrations run at start)"
dc start api
for i in $(seq 1 20); do
  st=$(docker inspect --format '{{.State.Health.Status}}' "$(dc ps -q api)" 2>/dev/null || true)
  [ "$st" = healthy ] && break
  sleep 3
done
echo "api health: ${st:-unknown}"
[ "${st:-}" = healthy ] || { echo "The API isn't healthy. The previous database is in /data/pre-restore-$ts inside the api-data volume." >&2; exit 1; }
echo "Restored $snap. Devices reconnect by themselves; messages queued after the backup was taken are not in it."

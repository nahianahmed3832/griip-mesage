#!/usr/bin/env bash
# Back up a running deployment: a consistent SQLite snapshot (taken with the server running), the encrypted
# attachment files and, if the server federates, its identity key (federation/), as one owner-only archive.
# Run from the deployment directory:
#   bash scripts/backup.sh [output directory]      # default: ./backups
# Not included, on purpose: .env and livekit/livekit.yaml (they hold secrets). Keep them separately.
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT=griip-internal-messaging
OUT="${1:-$DIR/backups}"
dc() { docker compose -p "$PROJECT" -f "$DIR/compose.yaml" "$@"; }
ts=$(date -u +%Y%m%dT%H%M%SZ)
name="griip-$ts"
umask 077
mkdir -p "$OUT"
# The snapshot is written inside the data volume first, verified, archived with the attachments, then removed.
dc exec -T api node dist/admin.js backup "/data/backups/$name.sqlite"
dc exec -T api node dist/admin.js verify-backup "/data/backups/$name.sqlite" > "$OUT/$name.verify.json"
# federation/ holds the server's identity key: without it, a restored server would have a different identity. reports.key
# (single server, batch B4) opens reported content: without it, restored reports can't be read.
dc exec -T api sh -c "cd /data && tar -czf - backups/$name.sqlite attachments \$(test -d federation && echo federation) \$(test -f reports.key && echo reports.key)" > "$OUT/$name.tar.gz"
dc exec -T api rm -f "/data/backups/$name.sqlite"
[ -s "$OUT/$name.tar.gz" ] || { echo "backup archive is empty" >&2; exit 1; }
echo "backup: $OUT/$name.tar.gz ($(du -h "$OUT/$name.tar.gz" | cut -f1))"
echo "check:  $OUT/$name.verify.json"
echo "Keep .env and livekit/livekit.yaml somewhere safe as well; they aren't in the archive."

#!/usr/bin/env bash
# Restore check for an off-site backup, on the owner's computer (never on production):
#   decrypt with the owner's age key, compare with the manifest, unpack, and check the snapshot with this repository's
#   server (verify-backup and table counts). Optionally starts a throwaway local server on it.
#   bash deploy/scripts/offsite-restore-check.sh <backup.tar.gz.age> <manifest.json> <age identity file> [--serve]
# Download first with the owner's profile, e.g.:
#   aws s3 cp --profile orbit-admin s3://<bucket>/orbit-db/<name>.tar.gz.age .
# Everything is unpacked into a new temporary folder, which is removed at the end unless --serve keeps it running.
set -euo pipefail
[ $# -ge 3 ] || { echo "usage: $0 <backup.tar.gz.age> <manifest.json> <age identity file> [--serve]" >&2; exit 2; }
ENC="$1"; MANIFEST="$2"; IDENTITY="$3"; SERVE="${4:-}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
command -v age >/dev/null || { echo "age is not installed" >&2; exit 1; }
[ -d "$ROOT/server/dist" ] || { echo "build the server first: cd server && npm run build" >&2; exit 1; }
umask 077
WORK="$(mktemp -d)"
[ "$SERVE" = "--serve" ] || trap 'rm -rf "$WORK"' EXIT

age -d -i "$IDENTITY" -o "$WORK/archive.tar.gz" "$ENC"
want=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["archive"]["sha256"])' "$MANIFEST")
got=$(shasum -a 256 "$WORK/archive.tar.gz" | cut -d' ' -f1)
[ "$want" = "$got" ] || { echo "MISMATCH: archive sha256 $got, manifest says $want" >&2; exit 1; }
echo "archive sha256 matches the manifest"
mkdir -p "$WORK/data"
tar -xzf "$WORK/archive.tar.gz" -C "$WORK/data"
snap="$(ls "$WORK"/data/backups/*.sqlite)"
cd "$ROOT/server"
node dist/admin.js verify-backup "$snap"
# Row counts of the restored snapshot against the manifest (opened as the database of a scratch data folder).
cp "$snap" "$WORK/data/griip.sqlite"
counts="$(DATA_DIR="$WORK/data" DATABASE_PATH="$WORK/data/griip.sqlite" node dist/admin.js table-counts)"
python3 - "$MANIFEST" "$counts" <<'PY'
import json, sys
want = json.load(open(sys.argv[1]))["tableCounts"]
got = json.loads(sys.argv[2])
# Only the manifest's tables: a newer server adds (empty) tables of its own migrations when it opens an older snapshot.
diff = {t: (want[t], got.get(t)) for t in want if want[t] != got.get(t) and t != "schema_migrations"}
if diff:
    print("COUNTS DIFFER:", json.dumps(diff)); sys.exit(1)
print(f"row counts match the manifest ({len(want)} tables)")
PY
if [ "$SERVE" = "--serve" ]; then
  echo "starting a throwaway server on 127.0.0.1:18199 with the restored data in $WORK (Ctrl-C stops it; delete the folder after)"
  HOST=127.0.0.1 PORT=18199 DATA_DIR="$WORK/data" REGISTRATION_MODE=invite MAIL_TRANSPORT=none exec node dist/index.js
fi
echo "restore check passed"

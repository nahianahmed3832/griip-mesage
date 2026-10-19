#!/usr/bin/env bash
# An encrypted copy of the server's data off this machine (docs/ORBIT_MONGODB_CUTOVER_PLAN_2026-10-19.md, §3):
#   1. a consistent, verified SQLite snapshot with the attachment files and the federation identity (as backup.sh), taken
#      through the running API or, with the API stopped for a write freeze, a one-off API container
#   2. a manifest: time, API version, schema version, rows per table, the archive's size and SHA-256 (no content)
#   3. encrypted with age to the owner's public key: this server can encrypt but never decrypt it
#   4. uploaded with a write-only key into the object-locked backup bucket; the unencrypted archive is then removed
# Run from the deployment directory, as root (the settings file holds the write-only key):
#   bash scripts/offsite-backup.sh [--label <name>] [--local <dir>]
# Settings: /root/.orbit-offsite.env (mode 600) or the environment:
#   OFFSITE_BUCKET, OFFSITE_PREFIX (default orbit-db/), OFFSITE_AGE_RECIPIENT (age1…),
#   AWS_SHARED_CREDENTIALS_FILE and AWS_PROFILE (the write-only key), AWS_REGION (default ap-southeast-1).
# --local <dir>: keep the encrypted result in <dir> instead of uploading (rehearsal).
# ORBIT_BACKUP_ARCHIVE=<file>: encrypt an existing archive instead of running backup.sh (rehearsal off the server).
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT=griip-internal-messaging
LABEL=scheduled
LOCAL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --label) LABEL="$2"; shift 2 ;;
    --local) LOCAL="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
[[ "$LABEL" =~ ^[a-z0-9-]{1,40}$ ]] || { echo "--label: lowercase letters, digits and - only" >&2; exit 2; }
if [ -f /root/.orbit-offsite.env ]; then
  # shellcheck disable=SC1091
  . /root/.orbit-offsite.env
fi
: "${OFFSITE_AGE_RECIPIENT:?set OFFSITE_AGE_RECIPIENT to the owner age public key (age1...)}"
[[ "$OFFSITE_AGE_RECIPIENT" =~ ^age1[a-z0-9]{58}$ ]] || { echo "OFFSITE_AGE_RECIPIENT doesn't look like an age public key" >&2; exit 2; }
command -v age >/dev/null || { echo "age is not installed (apt-get install age)" >&2; exit 1; }
if [ -z "$LOCAL" ]; then
  : "${OFFSITE_BUCKET:?set OFFSITE_BUCKET}"
  command -v aws >/dev/null || { echo "the AWS CLI is not installed" >&2; exit 1; }
fi
PREFIX="${OFFSITE_PREFIX:-orbit-db/}"
export AWS_REGION="${AWS_REGION:-ap-southeast-1}"

umask 077
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
ts=$(date -u +%Y%m%dT%H%M%SZ)
dc() { docker compose -p "$PROJECT" -f "$DIR/compose.yaml" "$@"; }

if [ -n "${ORBIT_BACKUP_ARCHIVE:-}" ]; then
  archive="$ORBIT_BACKUP_ARCHIVE"
  verify="${ORBIT_BACKUP_VERIFY:-}"
  version="${ORBIT_BACKUP_VERSION:-unknown}"
  counts="${ORBIT_BACKUP_COUNTS:-}"
  [ -n "$counts" ] || counts='{}'
else
  # The same snapshot as backup.sh, through the running API, or a one-off API container during a write freeze (API
  # stopped): either way the database is read through SQLite's online backup and verified.
  if [ -n "$(dc ps -q --status running api 2>/dev/null)" ]; then api() { dc exec -T api "$@"; }; else api() { dc run --rm --no-deps -T api "$@"; }; fi
  snap="griip-$ts"
  api node dist/admin.js backup "/data/backups/$snap.sqlite" >/dev/null
  api node dist/admin.js verify-backup "/data/backups/$snap.sqlite" > "$WORK/$snap.verify.json"
  api sh -c "cd /data && tar -czf - backups/$snap.sqlite attachments \$(test -d federation && echo federation)" > "$WORK/$snap.tar.gz"
  api rm -f "/data/backups/$snap.sqlite"
  archive="$WORK/$snap.tar.gz"
  verify="$WORK/$snap.verify.json"
  version="$(api node -e 'console.log(require("/app/package.json").version)')"
  counts="$(api node dist/admin.js table-counts)"
fi
[ -s "$archive" ] || { echo "no archive" >&2; exit 1; }

name="orbit-$LABEL-$ts"
sha=$(sha256sum "$archive" 2>/dev/null | cut -d' ' -f1 || shasum -a 256 "$archive" | cut -d' ' -f1)
bytes=$(wc -c < "$archive" | tr -d ' ')
{
  printf '{\n  "name": "%s",\n  "createdAt": "%s",\n  "label": "%s",\n  "apiVersion": "%s",\n' "$name" "$ts" "$LABEL" "$version"
  printf '  "archive": { "bytes": %s, "sha256": "%s" },\n' "$bytes" "$sha"
  printf '  "verify": %s,\n' "$( [ -n "$verify" ] && [ -s "$verify" ] && cat "$verify" || echo null)"
  printf '  "tableCounts": %s,\n' "$counts"
  printf '  "encryption": "age (X25519) to the owner'"'"'s key; the server cannot decrypt"\n}\n'
} > "$WORK/$name.manifest.json"

# The archive is encrypted before it leaves; the manifest (counts and hashes only) stays readable for checks.
age -r "$OFFSITE_AGE_RECIPIENT" -o "$WORK/$name.tar.gz.age" "$archive"
[ -z "${ORBIT_BACKUP_ARCHIVE:-}" ] && rm -f "$archive"

if [ -n "$LOCAL" ]; then
  mkdir -p "$LOCAL"
  cp "$WORK/$name.tar.gz.age" "$WORK/$name.manifest.json" "$LOCAL/"
  echo "kept: $LOCAL/$name.tar.gz.age (+ manifest)"
else
  aws s3 cp --only-show-errors "$WORK/$name.tar.gz.age" "s3://$OFFSITE_BUCKET/$PREFIX$name.tar.gz.age"
  aws s3 cp --only-show-errors --content-type application/json "$WORK/$name.manifest.json" "s3://$OFFSITE_BUCKET/$PREFIX$name.manifest.json"
  echo "uploaded: s3://$OFFSITE_BUCKET/$PREFIX$name.tar.gz.age (+ manifest)"
fi
echo "sha256 of the unencrypted archive: $sha ($bytes bytes)"

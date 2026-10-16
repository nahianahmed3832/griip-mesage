#!/usr/bin/env bash
# Assemble a self-contained deployment directory from this repository: compose.yaml, server/ (the API
# build context), the LiveKit template and these scripts. Run it on the machine that will host Griip
# Message, or copy the result there.
#   deploy/scripts/bundle.sh /opt/griip-internal-messaging
# Re-running it on an existing deployment updates code and scripts only: it never touches .env,
# livekit/livekit.yaml, backups/ or any Docker volume.
set -euo pipefail
DEST="${1:?usage: bundle.sh <deployment directory>}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$DEST/livekit" "$DEST/scripts" "$DEST/server"
chmod 750 "$DEST"
# server/: source, migrations and build files only (no node_modules, build output, tests or local data).
rsync -a --delete --exclude node_modules --exclude dist --exclude data --exclude test --exclude coverage --exclude .DS_Store \
  "$ROOT/server/" "$DEST/server/"
cp "$ROOT/deploy/compose.yaml" "$ROOT/deploy/.env.example" "$DEST/"
cp "$ROOT/deploy/livekit/livekit.yaml.template" "$DEST/livekit/"
cp "$ROOT/deploy/scripts/"*.sh "$DEST/scripts/"
chmod 755 "$DEST/scripts/"*.sh
echo "Bundled into $DEST"
if [ -f "$DEST/.env" ]; then
  echo "Kept the existing .env and livekit/livekit.yaml. Next: bash scripts/validate-env.sh"
else
  echo "Next: cd $DEST && sh scripts/gen-config.sh, edit .env, sh scripts/gen-config.sh again, bash scripts/validate-env.sh"
fi

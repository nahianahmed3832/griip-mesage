#!/usr/bin/env bash
# A local Meldiq server for testing a debug build on a phone over USB (docs/ENVIRONMENTS.md, development).
# Never production: its own data folder, open sign-up, and emails written to files instead of sent.
#
#   server/scripts/phone-test-server.sh          start the server and connect the phone (adb reverse tcp:8080)
#   server/scripts/phone-test-server.sh code     print the newest emailed code (sign-up, sign-in on a new phone)
#
# DATA_DIR (default ~/.meldiq-phone-test) keeps accounts between runs; delete the folder to start over.
set -euo pipefail
DATA_DIR="${DATA_DIR:-$HOME/.meldiq-phone-test}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
cd "$(dirname "$0")/.."

if [ "${1:-}" = "code" ]; then
  newest=$(ls -t "$DATA_DIR/mail"/*.eml 2>/dev/null | head -1 || true)
  [ -n "$newest" ] || { echo "No email yet in $DATA_DIR/mail"; exit 1; }
  grep -o '[0-9]\{6\}' "$newest" | head -1
  exit 0
fi

mkdir -p "$DATA_DIR"
"$ADB" reverse tcp:8080 tcp:8080 || echo "No phone connected yet: plug it in and run: $ADB reverse tcp:8080 tcp:8080"
echo "Meldiq test server on http://127.0.0.1:8080 (data: $DATA_DIR). Ctrl+C stops it."
HOST=127.0.0.1 PORT=8080 MAIL_TRANSPORT=file REGISTRATION_MODE=open DATA_DIR="$DATA_DIR" exec npx tsx src/index.ts

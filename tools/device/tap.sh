#!/usr/bin/env bash
# Tap the first on-screen element whose text or content-desc matches $2 (regex) on device $1.
# Usage: tools/device/tap.sh <serial> "<label regex>"   — prints the bounds it tapped, or exits 1.
set -euo pipefail
D="$1"; LABEL="$2"; TRIES="${3:-4}"
for attempt in $(seq 1 "$TRIES"); do
adb -s "$D" shell uiautomator dump /sdcard/griip-ui.xml >/dev/null 2>&1 || true
XML=$(adb -s "$D" exec-out cat /sdcard/griip-ui.xml 2>/dev/null || true); adb -s "$D" shell rm -f /sdcard/griip-ui.xml
X=""; Y=""
read -r X Y < <(python3 -c '
import re,sys
xml,label=sys.argv[1],sys.argv[2]
for n in re.finditer(r"<node [^>]*>", xml):
    t=n.group(0)
    txt=(re.search(r"text=\"([^\"]*)\"",t) or [None,""])[1]
    desc=(re.search(r"content-desc=\"([^\"]*)\"",t) or [None,""])[1]
    if re.fullmatch(label, txt) or re.fullmatch(label, desc):
        x1,y1,x2,y2=map(int,re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",t).groups())
        print((x1+x2)//2,(y1+y2)//2); break
' "$XML" "$LABEL") || true
if [ -n "${X:-}" ]; then adb -s "$D" shell input tap "$X" "$Y"; echo "tapped '$LABEL' at $X,$Y"; exit 0; fi
sleep 1
done
echo "not found: $LABEL"; exit 1

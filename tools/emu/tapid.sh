#!/usr/bin/env bash
# tapid.sh <resource-id | text=... | desc=...> [index] : dump the UI, tap the matching node's centre.
# Re-dumps every time on purpose: the soft keyboard moves dialogs, so stale coordinates miss.
source "$(dirname "$0")/env.sh"; dump_ui
xy=$(python3 -I - "$EMU_WORK/ui.xml" "$1" "${2:-0}" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path, key, idx = sys.argv[1], sys.argv[2], int(sys.argv[3])
hits = []
for n in ET.parse(path).getroot().iter('node'):
    if key.startswith('text='): ok = n.get('text') == key[5:]
    elif key.startswith('desc='): ok = n.get('content-desc') == key[5:]
    else: ok = n.get('resource-id', '').split('/')[-1] == key
    if ok:
        x1,y1,x2,y2 = map(int, re.findall(r'\d+', n.get('bounds'))); hits.append(f"{(x1+x2)//2} {(y1+y2)//2}")
print(hits[idx] if len(hits) > idx else '')
PY
)
[ -n "$xy" ] || { echo "not found: $1"; exit 1; }
adb shell input tap $xy; echo "tap $1 at $xy"

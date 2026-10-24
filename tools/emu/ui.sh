#!/usr/bin/env bash
# ui.sh : list every node with text / content-desc / a clickable flag, with its centre point
source "$(dirname "$0")/env.sh"; dump_ui
python3 -I - "$EMU_WORK/ui.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).getroot().iter('node'):
    t, d, i = n.get('text'), n.get('content-desc'), n.get('resource-id','')
    x1,y1,x2,y2 = map(int, re.findall(r'\d+', n.get('bounds')))
    if t or d or n.get('clickable') == 'true':
        extra = f" checked={n.get('checked')}" if n.get('checkable') == 'true' else ''
        print(f"({(x1+x2)//2},{(y1+y2)//2}) {n.get('class').split('.')[-1]} id={i.split('/')[-1]} text={t!r} desc={d!r}{' CLICK' if n.get('clickable')=='true' else ''}{extra}")
PY

#!/usr/bin/env bash
# Build TopoDroid (read in place from the clone) behind the machine-wide heavy lock.
#   ./build.sh apk    assembleDebug  -> build/outputs/apk/debug/*.apk
#   ./build.sh test   testDebugUnitTest (JUnit 4, src/test/java)
#   ./build.sh lint   lintDebug, prints the error/warning counts, exits 1 if errors rose
#                     above LINT-BASELINE.txt (lint-summary re-checks the last report)
# Extra arguments are passed to Gradle (e.g. -PtopodroidDir=/path/to/worktree).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-/home/devuser1/.local/opt/android-sdk}"
lock=/srv/data/silexgis/heavy.lock
mkdir -p "$here/logs"
cmd="${1:-}"; shift || true
extra="$*"

run() { # $1 = task, $2 = log name
  local log="$here/logs/$2.log"
  echo "[$(date +%T)] $1 (log: $log)"
  local start=$SECONDS
  local rc=0
  flock -o "$lock" nice -n 19 bash -c "cd '$here' && ./gradlew $1 $extra > '$log' 2>&1" || rc=$?
  echo "[$(date +%T)] $1 exit $rc after $((SECONDS-start)) s"
  grep -E 'BUILD (SUCCESSFUL|FAILED)' "$log" | tail -1 || true
  return $rc
}

lint_summary() {
  local xml="$here/build/reports/lint-results-debug.xml"
  [ -f "$xml" ] || { echo "no lint report at $xml"; return 1; }
  python3 -I - "$xml" <<'PY'
import sys, collections, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
sev = collections.Counter(); ids = collections.Counter()
for i in root.iter('issue'):
    s = i.get('severity'); sev[s] += 1
    if s in ('Error', 'Fatal'): ids[i.get('id')] += 1
err = sev['Error'] + sev['Fatal']
print(f"errors={err} warnings={sev['Warning']} information={sev['Information']}")
for k, v in ids.most_common(): print(f"  error {k}: {v}")
PY
}

lint_compare() { # fails when the error total or any error id rises above LINT-BASELINE.txt
  python3 -I - "$here/LINT-BASELINE.txt" "$here/logs/lint-summary.txt" <<'PY'
import sys, re
def read(p):
    tot=None; ids={}
    for line in open(p):
        m=re.match(r'errors=(\d+)', line)
        if m: tot=int(m.group(1))
        m=re.match(r'\s+error (\S+): (\d+)', line)
        if m: ids[m.group(1)]=int(m.group(2))
    return tot, ids
bt, bi = read(sys.argv[1]); nt, ni = read(sys.argv[2])
worse=[f"{k}: {bi.get(k,0)} -> {v}" for k,v in ni.items() if v > bi.get(k,0)]
print(f"lint errors: baseline {bt}, now {nt}")
for w in worse: print("  NEW ERRORS", w)
sys.exit(1 if worse or nt > bt else 0)
PY
}

case "$cmd" in
  apk)  run assembleDebug assembleDebug
        ls -l "$here"/build/outputs/apk/debug/*.apk ;;
  test) run testDebugUnitTest testDebugUnitTest
        python3 -I - "$here/build/test-results/testDebugUnitTest" <<'PY'
import sys, pathlib, xml.etree.ElementTree as ET
t=f=e=s=0
for p in pathlib.Path(sys.argv[1]).glob('*.xml'):
    r=ET.parse(p).getroot(); t+=int(r.get('tests'));f+=int(r.get('failures'));e+=int(r.get('errors'));s+=int(r.get('skipped'))
print(f"tests={t} failures={f} errors={e} skipped={s}")
PY
        ;;
  lint) run lintDebug lintDebug; lint_summary | tee "$here/logs/lint-summary.txt"
        lint_compare ;;
  lint-summary) lint_summary | tee "$here/logs/lint-summary.txt"; lint_compare ;;
  *) echo "usage: $0 apk|test|lint|lint-summary [gradle args]"; exit 2 ;;
esac

#!/usr/bin/env bash
# fill.sh id value [id value ...] : tap each field (by resource id), clear it, type the value.
# A space is typed as %s; quote shell metacharacters twice, e.g. "'Test%sA.;%sTest%sB.'"
source "$(dirname "$0")/env.sh"
while [ $# -ge 2 ]; do
  "$here/tapid.sh" "$1" >/dev/null || exit 1; sleep 0.8
  adb shell input keyevent KEYCODE_MOVE_END
  for i in 1 2 3 4 5 6 7 8 9 10 11 12; do adb shell input keyevent KEYCODE_DEL; done
  adb shell input text "$2"; sleep 0.5
  shift 2
done

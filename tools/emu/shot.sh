#!/usr/bin/env bash
# shot.sh NAME : screenshot to $EMU_WORK/shots/NAME.png plus a 480 px wide NAME-s.png for reading
source "$(dirname "$0")/env.sh"; d="$EMU_WORK/shots"
adb exec-out screencap -p > "$d/$1.png"
python3 -I -c "import sys; from PIL import Image; im=Image.open(sys.argv[1]); im.resize((480, im.height*480//im.width)).save(sys.argv[2])" "$d/$1.png" "$d/$1-s.png"
echo "$d/$1-s.png"

# sourced by the other helpers: adb on PATH and a work dir for dumps and screenshots
export ANDROID_HOME="${ANDROID_HOME:-/home/devuser1/.local/opt/android-sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
EMU_WORK="${EMU_WORK:-/srv/data/silexgis/scratch/topodroid-integration/emu}"
mkdir -p "$EMU_WORK/shots"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dump_ui() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml > "$EMU_WORK/ui.xml"; }

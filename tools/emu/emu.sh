#!/usr/bin/env bash
# emu.sh start|stop|install|grant|status : the one headless emulator TopoDroid lanes share
source "$(dirname "$0")/env.sh"
AVD=${AVD:-speleo_api36_pixel7pro}
APK=${APK:-$here/../../build/outputs/apk/debug/topodroid-silexgis-debug.apk}
PKG=com.topodroid.TDX
case "$1" in
  start)
    if pgrep -f 'qemu-system' >/dev/null; then echo "an emulator is already running:"; pgrep -af qemu-system; exit 1; fi
    echo "load: $(cut -d' ' -f1-3 /proc/loadavg)"
    setsid nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -no-window -no-audio -gpu swiftshader_indirect \
      -no-snapshot-save > "$EMU_WORK/emulator.log" 2>&1 < /dev/null &
    timeout 300 adb wait-for-device
    for i in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 5; done
    adb shell getprop sys.boot_completed ;;
  stop) adb emu kill ;;
  install) adb install -r "$APK" ;;
  grant)
    adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow
    for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION BLUETOOTH_CONNECT BLUETOOTH_SCAN CAMERA RECORD_AUDIO \
             READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE; do adb shell pm grant $PKG android.permission.$p; done ;;
  status) pgrep -af qemu-system; adb devices ;;
  *) echo "usage: $0 start|stop|install|grant|status"; exit 2 ;;
esac

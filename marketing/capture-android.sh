#!/bin/bash
# Captures Google Play phone screenshots from a running emulator (Pixel 2 profile = 1080x1920, 9:16).
set -euo pipefail
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=com.robertlangat.todoplanner
OUT=store/raw/phone
mkdir -p "$OUT"

adb install -r "$APK"
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
demo enter
demo clock -e hhmm 0941
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4
demo notifications -e visible false

for scene in today focus calendar planner editor mine; do
  adb shell am force-stop "$PKG"
  adb shell pm clear "$PKG" >/dev/null          # fresh install state -> fresh demo data
  adb shell am start -W -n "$PKG/.MainActivity" -e ScreenshotScene "$scene" >/dev/null
  sleep 8
  adb exec-out screencap -p > "$OUT/$scene.png"
  echo "captured $scene"
done
demo exit

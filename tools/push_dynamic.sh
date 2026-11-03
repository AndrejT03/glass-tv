#!/bin/sh
# Puts Apple's dynamic desktop pictures (the ones that follow the sun: Big Sur, Catalina, The Beach…)
# on the TV (or an emulator), for the Dynamic Wallpaper screen saver and the iPhone app's widgets.
# They are downloaded from Apple's servers as macOS downloads them, once, into ~/Library/Caches/GlassTV
# (tools/dynamic_wallpapers.py), and copied from there: Apple's pictures stay out of the app and this
# project, as Apple's fonts do (push_fonts.sh).
# Usage: tools/push_dynamic.sh <adb serial>, e.g. tools/push_dynamic.sh 192.168.100.125:5555
set -e
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
DIR=/sdcard/Android/data/com.andrej.glasstv/files/dynamic
OUT=$(python3 -I "$(dirname "$0")/dynamic_wallpapers.py")
"$ADB" -s "$1" shell rm -rf "$DIR"
# adb may not make folders under Android/data itself (secure_mkdirs): the shell makes them.
for d in "$OUT"/*/; do
    id=$(basename "$d")
    "$ADB" -s "$1" shell mkdir -p "$DIR/$id/tv" "$DIR/$id/phone"
    "$ADB" -s "$1" push "$d"tv/*.jpg "$DIR/$id/tv/"
    "$ADB" -s "$1" push "$d"phone/*.jpg "$DIR/$id/phone/"
done
# The manifest last, so Glass never lists a picture whose frames are still on their way.
"$ADB" -s "$1" push "$OUT/dynamic.json" "$DIR/dynamic.json"

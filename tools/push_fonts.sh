#!/bin/sh
# Copies Apple's SF Pro and SF Pro Rounded from this Mac to the TV (or an emulator), where the
# launcher uses them for all of its text and its clocks. Apple licenses these fonts for its own
# platforms, so the app doesn't include them; this puts your Mac's copies on your own TV.
# Usage: tools/push_fonts.sh <adb serial>, e.g. tools/push_fonts.sh 192.168.100.125:5555
set -e
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
DIR=/sdcard/Android/data/com.andrej.glasstv/files/fonts
"$ADB" -s "$1" shell mkdir -p "$DIR"
for f in SFNS.ttf SFNSRounded.ttf; do
    "$ADB" -s "$1" push "/System/Library/Fonts/$f" "$DIR/$f"
done
# The fonts are picked up when the launcher starts.
"$ADB" -s "$1" shell am force-stop com.andrej.glasstv

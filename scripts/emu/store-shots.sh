#!/usr/bin/env bash
# SUPERSEDED in v3.1.1 by scripts/emu/kit_shots.py (same 8 screens, any app language, taps found by translated text).
# Store screenshots 1-8 (RELEASE_KIT 8a) into build/store-raw/<lang>/ at 1080x2160 (2:1). Then run
# `swift scripts/make-store-assets.swift` for the plain + captioned PNGs, captions.json and the feature graphics.
# Usage: scripts/emu/store-shots.sh <en|de>
# Before: emulator-5570 booted in that language (de: adb root; adb shell setprop persist.sys.locale de-DE; adb reboot),
#   release build installed, accessibility GRANTED, dimming OFF, the widget on the first home page, schedule set to
#   Sunset -> Sunrise with 30 min fade but switched OFF, both tiles added. Coordinates are for the Pixel 9 AVD at
#   1080x2160 and the English app UI. Ends with accessibility REVOKED (shot 8 needs it) - run grant afterwards.
# Known artefact: after `wm size` the launcher may show the widget twice (v3.1 and launch notes).
set -uo pipefail
source "$(dirname "$0")/common.sh"
L=${1:?en or de}; O="$ROOT/build/store-raw/$L"; mkdir -p "$O"
cap() { sleep "${2:-2}"; $A exec-out screencap -p >"$O/$1.png"; echo "shot $1"; }
app() { $A shell am start -n com.plainapps.dimmer/.MainActivity >/dev/null 2>&1; sleep 1.5; }
$A shell wm size 1080x2160; demo
for k in $($A shell dumpsys notification --noredact | grep -oE "key=-1\|android\|[0-9]+\|null\|1000" | sort -u | sed 's/key=//'); do
  $A shell "cmd notification snooze --for 86400000 '$k'" >/dev/null   # "Serial console enabled" etc.
done
$A shell input keyevent HOME; sleep 0.5; app
$A shell input tap 495 948; cap 01 2.5                                   # Reading preset chip -> dimming on
$A shell input tap 778 1093; cap 02                                      # Candlelight (layout with the Pause row)
$A shell am start -a android.intent.action.DIAL -d tel:5550123 >/dev/null; cap 03 2.5
$A shell input keyevent BACK; sleep 1; $A shell cmd statusbar expand-notifications; cap 04
$A shell cmd statusbar collapse; sleep 1; shake; sleep 1.5; app
$A shell input tap 780 305; sleep 1.5                                    # Settings
y=$(ui find '^Dim on a schedule' | head -1 | awk -F', ' '{print $3}')
$A shell input tap 948 "$y"; sleep 2.5; shake; sleep 1.5                 # schedule on (dims inside the window) -> shake off
$A shell input swipe 540 1700 540 900 500; cap 05 1.5
$A shell input keyevent BACK; sleep 1.5; $A shell input swipe 802 948 802 948 900; cap 06 1.5   # long-press: preset menu
$A shell input keyevent BACK; sleep 1; $A shell input keyevent HOME; sleep 1.5
$A shell input tap 146 280; cap 07 2.5                                   # widget power button
shake; sleep 1.5
revoke; app
ui tap '^Set up access' >/dev/null; cap 08 2
$A shell input keyevent BACK

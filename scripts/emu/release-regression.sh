#!/usr/bin/env bash
# Release regression on emulator-5570 (launch step 7). Installs the RELEASE apk over the existing app (install -r, keeps
# data) and checks: no debug residue, accessibility config, DECLINE path, real-UI consent, overlay above status bar and
# shade, hybrid backlight, notification controls (Darker, Pause 15 min, Resume, Pause then Off), timed pause expiry
# (clock moved +16 min, needs adb root), shake to turn off, tile, automation ignored while its switch is off, and a real
# reboot with "Resume after restart" ON inside the schedule window (comes back, then shake -> off).
# Usage: scripts/emu/release-regression.sh [apk]   (default dist/dimmer-<versionName>-release.apk)
# Before: AVD Dimmer_API_36 booted on port 5570, English UI, 1080x2424 (wm size reset), schedule ON (Sunset -> Sunrise)
#   and the clock inside that window (evening/night), "Allow other apps to control Dimmer" OFF, dimming OFF.
#   Notification-button taps use fixed coordinates for this AVD with the Dimmer notification first in the shade (other
#   notifications are snoozed here). Never run while another session uses emulator-5570.
# Leaves: dimming off, accessibility granted, Resume after restart OFF, auto_time 1. Screenshots: build/regression/.
set -uo pipefail
source "$(dirname "$0")/common.sh"
version=$(grep -m1 'versionName = ' "$ROOT/app/build.gradle.kts" | sed 's/.*"\(.*\)".*/\1/')
APK=${1:-$ROOT/dist/dimmer-$version-release.apk}
OUT="$ROOT/build/regression"; mkdir -p "$OUT"
P=0; F=0
t() { local d=$1; shift; if "$@"; then P=$((P+1)); echo "PASS  $d"; else F=$((F+1)); echo "FAIL  $d"; fi; }
lg() { $A logcat -d -s DimmerV3 | grep -qF -- "$1"; }          # since the last mark
mark() { $A logcat -c; }
ovl() { $A shell dumpsys window windows | grep -qE "Window\{[0-9a-f]+ u0 com.plainapps.dimmer\}"; }
no_ovl() { ! ovl; }
above_bars() { $A shell dumpsys window windows | grep -E "^  Window #" |
  awk '/u0 com.plainapps.dimmer\}/{d=NR} /u0 StatusBar\}/{s=NR} /u0 NotificationShade\}/{n=NR} END{exit !(d && d<s && d<n)}'; }
ovr_is() { [ "$(override)" = "$1" ]; }
focus_dimmer() { $A shell dumpsys window | grep -m1 mCurrentFocus | grep -q "com.plainapps.dimmer/com.plainapps.dimmer.MainActivity"; }
chip() {   # tap the preset chip (the LAST element with that text: the first can be the active-preset line)
  local xy; xy=$(ui find "^$1\\|" | tail -1 | awk -F', ' '{print $2, $3}'); [ -n "$xy" ] || return 1
  $A shell input tap $xy; }
shot() { $A exec-out screencap -p >"$OUT/$1.png"; }
app() { $A shell am start -n com.plainapps.dimmer/.MainActivity >/dev/null 2>&1; sleep 2; }
shade() { $A shell cmd statusbar expand-notifications; sleep 1.5; }
collapse() { $A shell cmd statusbar collapse; sleep 1; }
# Buttons of the first notification, 1080x2424, one-line title (measured on this AVD 2026-10-11).
nb() { local x; case $1 in lighter) x=236;; darker) x=418;; pause) x=657;; resume) x=242;; off) x=409;; esac
  $A shell input tap "$x" 1051; sleep 2.5; }

echo "== R0 install $APK"
t "install -r release apk" sh -c "$A install -r '$APK' | grep -q Success"
sleep 3
t "installed versionName $version" sh -c "$A shell dumpsys package com.plainapps.dimmer | grep -q 'versionName=$version'"
t "pkgFlags without DEBUGGABLE" sh -c "! $A shell dumpsys package com.plainapps.dimmer | grep pkgFlags | grep -q DEBUGGABLE"
t "run-as refused (not debuggable)" sh -c "! $A shell run-as com.plainapps.dimmer true 2>/dev/null"
$A shell pm grant com.plainapps.dimmer android.permission.POST_NOTIFICATIONS
$A shell input keyevent WAKEUP; $A shell wm dismiss-keyguard; sleep 1
for k in $($A shell dumpsys notification --noredact | grep -oE "key=[^ ]+" | sed 's/key=//' | grep -v "com.plainapps.dimmer" | sort -u); do
  $A shell "cmd notification snooze --for 86400000 '$k'" >/dev/null 2>&1
done
ovl && { shake; sleep 2; }

echo "== R1 accessibility config (service bound after the update)"
for _ in 1 2 3 4 5; do bound && break; sleep 2; done
t "service bound after install -r" bound
t "eventTypes empty, capabilities=0 (cannot retrieve window content)" sh -c \
  "$A shell dumpsys accessibility | grep 'Bound services' | grep 'label=Dimmer' | grep -q 'capabilities=0, eventTypes=,'"

echo "== R2 revoke through Settings, then the DECLINE path"
revoke; t "revoked through the real Settings UI" no_ovl
t "service not bound" sh -c '! { '"$A"' shell dumpsys accessibility | grep "Bound services" | grep -q "label=Dimmer"; }'
app; mark
ui tap '^Set up access' >/dev/null; sleep 2
t "disclosure shown" sh -c "python3 '$ROOT/scripts/emu/ui.py' find '^Dimmer needs Accessibility access' | grep -q Dimmer"
shot r2-disclosure
$A shell input swipe 540 1900 540 300 500; sleep 1.2
ui tap '^Not now' >/dev/null; sleep 2
t "Not now: still in Dimmer (Settings not opened)" focus_dimmer
t "Not now: service still not bound" sh -c '! { '"$A"' shell dumpsys accessibility | grep "Bound services" | grep -q "label=Dimmer"; }'
t "Not now: Set up access card still there" sh -c "python3 '$ROOT/scripts/emu/ui.py' find '^Set up access' | grep -q Set"
t "Not now: no accessibility settings route logged" sh -c "! $A logcat -d -s DimmerV3 | grep -q 'opened accessibility settings'"
t "preset chip tapped without access" chip Reading; sleep 2
t "preset tap without access: no overlay" no_ovl
shot r2-declined-home

echo "== R3 consent through the real UI"
t "granted: disclosure -> Agree -> Use Dimmer -> Allow -> bound" grant

echo "== R4 dimming on (app preset), overlay, hybrid backlight"
app; mark; chip Reading; sleep 2.5
t "turn on (manual) logged" lg "turn on (manual)"
t "overlay window present" ovl
t "overlay above StatusBar and NotificationShade" above_bars
t "window brightness override 0.0 (backlight part)" ovr_is 0.0
t "notification actions LIGHTER, DARKER, PAUSE" lg "[LIGHTER, DARKER, PAUSE]"
shade; shot r4-shade-dimmed

echo "== R5 notification controls"
mark; nb darker
t "Darker from the notification" lg "(notification): 45 -> "
mark; nb pause
t "Pause 15 min from the notification" lg "pause 15 min (notification)"
t "paused: overlay gone" no_ovl
t "paused: brightness override released (NaN)" ovr_is NaN
shot r5-paused
mark; nb resume
t "Resume from the notification" lg "resume (notification)"
t "resumed: overlay back" ovl
mark; nb pause; nb off
t "Pause then Off from the notification" lg "turn off (notification)"
t "off: no overlay, override NaN" sh -c "! $A shell dumpsys window windows | grep -qE 'Window\{[0-9a-f]+ u0 com.plainapps.dimmer\}' && [ \"\$($A shell dumpsys display | grep -oE 'mWindowManagerBrightnessOverride=[^ ,]*' | head -1 | cut -d= -f2)\" = NaN ]"
collapse

echo "== R6 timed pause ends by itself (clock +16 min)"
app; chip Reading; sleep 2.5; shade; mark; nb pause; collapse
t "paused for 15 min" lg "pause 15 min (notification)"
$A root >/dev/null 2>&1; sleep 2; $A wait-for-device
$A shell settings put global auto_time 0
now=$($A shell date +%s | tr -d '\r'); tz=$($A shell getprop persist.sys.timezone | tr -d '\r')
$A shell date "$(TZ=$tz date -r $((now + 960)) +%m%d%H%M%Y.%S)" >/dev/null; sleep 4
t "pause ended by the clock" lg "pause ended"
t "dimming back after the pause" ovl
$A shell settings put global auto_time 1; sleep 3

echo "== R7 shake to turn off"
mark; shake; sleep 2
t "shake -> turn off (shake)" lg "turn off (shake)"
t "after shake: no overlay" no_ovl

echo "== R8 Quick Settings tile"
$A shell cmd statusbar add-tile com.plainapps.dimmer/.DimmerTileService >/dev/null 2>&1
shade; mark; $A shell cmd statusbar click-tile com.plainapps.dimmer/.DimmerTileService; sleep 2.5
t "tile -> turn on (tile)" lg "turn on (tile)"
mark; $A shell cmd statusbar click-tile com.plainapps.dimmer/.DimmerTileService; sleep 2.5
t "tile -> turn off (tile)" lg "turn off (tile)"
collapse

echo "== R9 automation ignored while its switch is off"
mark; $A shell am broadcast -a com.plainapps.dimmer.action.ON -p com.plainapps.dimmer >/dev/null; sleep 2
t "automation ON ignored (no turn on, no overlay)" sh -c "! $A logcat -d -s DimmerV3 | grep -q 'turn on' && ! $A shell dumpsys window windows | grep -qE 'Window\{[0-9a-f]+ u0 com.plainapps.dimmer\}'"

echo "== R10 real reboot with Resume after restart ON inside the schedule window"
settings_row() {   # open Settings and scroll until the row exists; prints "x y checked" of its switch
  app; $A shell input tap 780 310; sleep 1.5
  for _ in 1 2 3 4 5 6; do ui switch "$1" 2>/dev/null | grep -E '^[0-9]+ [0-9]+ (true|false)$' && return 0
    $A shell input swipe 540 1800 540 900 400; sleep 1; done; return 1; }
set_switch() { local r; r=$(settings_row "$1") || return 1; set -- "$1" $r "$2"
  [ "$4" = "$5" ] || { $A shell input tap "$2" "$3"; sleep 1.5; }
  [ "$(ui switch "$1" | awk '{print $3}')" = "$5" ]; }
sched_on() { [ "$(settings_row '^Dim on a schedule' | awk '{print $3}')" = true ]; }
t "Dim on a schedule is ON (precondition)" sched_on
t "Resume after restart switched ON" set_switch '^Resume after restart' true
$A shell input keyevent BACK; sleep 1; app; mark; chip Reading; sleep 2.5
t "on before reboot" ovl
$A reboot; sleep 5; $A wait-for-device
for _ in $(seq 1 60); do [ "$($A shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 2; done
sleep 12
t "after reboot: service bound by itself" bound
t "after reboot: schedule TURN_ON (resume)" lg "schedule TURN_ON"
t "after reboot: overlay back by itself (schedule preset)" ovl
shot r10-after-reboot
$A shell input keyevent WAKEUP; sleep 1; mark; shake; sleep 2
t "after reboot: shake -> turn off (shake)" lg "turn off (shake)"
t "after reboot: overlay gone" no_ovl
$A shell wm dismiss-keyguard; sleep 1
t "Resume after restart switched back OFF" set_switch '^Resume after restart' false
$A shell input keyevent BACK; sleep 1; $A shell input keyevent HOME

echo "== RESULT: $P passed, $F failed (screenshots in build/regression/)"
[ "$F" -eq 0 ]

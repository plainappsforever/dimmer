# Shared helpers for the Dimmer emulator scripts (source this). Emulator: AVD Dimmer_API_36 on port 5570 only.
export ANDROID_SERIAL=emulator-5570
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/../.." && pwd)"
A="adb -s emulator-5570"
ui() { python3 "$ROOT/scripts/emu/ui.py" "$@"; }
# Shake probe (CLAUDE.md): three strong pairs, then rest. Dimming must be visible and the screen on.
shake() {
  for _ in 1 2 3; do $A emu sensor set acceleration 30:0:9.8 >/dev/null; sleep 0.15; $A emu sensor set acceleration -30:0:9.8 >/dev/null; sleep 0.15; done
  $A emu sensor set acceleration 0:9.8:0 >/dev/null
}
# Clean status bar for store shots (SystemUI demo mode). `demo exit` leaves it.
demo() {
  if [ "${1:-}" = exit ]; then $A shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null; return; fi
  $A shell settings put global sysui_demo_allowed 1
  for c in "enter" "clock -e hhmm 2230" "battery -e level 100 -e plugged false" "network -e wifi show -e level 4" \
           "network -e mobile hide" "notifications -e visible false"; do
    # shellcheck disable=SC2086
    $A shell am broadcast -a com.android.systemui.demo -e command $c >/dev/null
  done
}
override() { $A shell dumpsys display | grep -oE "mWindowManagerBrightnessOverride=[^ ,]*" | head -1 | cut -d= -f2; }
bound() { $A shell dumpsys accessibility | grep "Bound services" | grep -q "label=Dimmer"; }
dlog() { $A logcat -d -s DimmerV3 | cut -c32-; }
# Real-UI grant: disclosure -> Agree and open settings -> Dimmer -> Use Dimmer -> Allow (dimming must be off).
grant() {
  $A shell input keyevent HOME; sleep 0.5; $A shell am start -n com.plainapps.dimmer/.MainActivity >/dev/null 2>&1; sleep 2
  ui tap '^Set up access' >/dev/null || { echo "no Set up access card"; return 1; }; sleep 2
  $A shell input swipe 540 1900 540 300 500; sleep 1.2
  ui tap '^Agree and open settings' >/dev/null; sleep 2.5
  ui tap '^Dimmer\|\|android:id/title' >/dev/null; sleep 2
  ui tap 'com.android.settings:id/main_switch_bar' >/dev/null; sleep 2
  ui tap 'accessibility_permission_enable_allow_button' >/dev/null; sleep 2
  for _ in 1 2; do $A shell input keyevent BACK; sleep 0.8; done
  bound
}
# Real-UI revoke: Settings > Accessibility > Dimmer > Use Dimmer off > confirm.
revoke() {
  $A shell am start -a android.settings.ACCESSIBILITY_SETTINGS >/dev/null; sleep 2
  ui tap '^Dimmer\|\|android:id/title' >/dev/null; sleep 1.5
  ui tap 'com.android.settings:id/main_switch_bar' >/dev/null; sleep 1.5
  ui tap 'android:id/button1' >/dev/null; sleep 1.5
  for _ in 1 2 3; do $A shell input keyevent BACK; sleep 0.6; done
  ! bound
}

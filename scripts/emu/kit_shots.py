#!/usr/bin/env python3
"""The 8 store screens (RELEASE_KIT 8a) in ANY app language, on emulator-5570 (v3.1.1, replaces the English-only
coordinates of store-shots.sh). Every tap is found by the translated text from res/values-<q>/strings.xml, so the same
script serves the layout check of a translation and the store screenshots.

Usage: python3 scripts/emu/kit_shots.py <lang> <out_dir> [--app-locale] [--extra]
  lang          en, de, ar, ja, ... (names as in scripts/make-strings.py; en = res/values)
  --app-locale  set only Dimmer's language (cmd locale set-app-locales; Android UI stays as it is) and reset it after.
                Without it the DEVICE must already run in that language (store shots: Android UI translated too).
  --extra       also capture the full disclosure (scrolled) and every Settings page (layout check: x01, x02, ...).
Before: AVD Dimmer_API_36 booted, the app installed, Accessibility access GRANTED, dimming OFF, Dimmer's widget on one
  of the first three home pages (on the AVD: the second page, two widgets). Output: <out_dir>/01.png ... 08.png at 1080x2160 (wm size; reset at the end).
Uses uiautomator dumps ONLY while dimming is off (a dump rebinds accessibility services and stops dimming, CLAUDE.md).
Dimming is switched on by the automation broadcast (switch "Allow other apps to control Dimmer" is set on for the run
and back off at the end), by the schedule, or by the widget - never by tapping during dimming.
Leaves: access granted, dimming off, schedule ON (Sunset -> Sunrise as before; regression precondition), automation OFF.
"""
import os, re, subprocess, sys, time, xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
QUAL = {"en": "", "de": "-de", "es": "-es", "pt-BR": "-pt-rBR", "fr": "-fr", "it": "-it", "tr": "-tr", "ru": "-ru",
        "id": "-in", "hi": "-hi", "ja": "-ja", "zh-CN": "-zh-rCN", "ko": "-ko", "ar": "-ar", "vi": "-vi"}
ADB = ["adb", "-s", "emulator-5570"]
PKG = "com.plainapps.dimmer"

args = [a for a in sys.argv[1:] if not a.startswith("--")]
flags = {a for a in sys.argv[1:] if a.startswith("--")}
LANG, OUT = args[0], args[1]
os.makedirs(OUT, exist_ok=True)

def strings(qual):
    p = os.path.join(ROOT, f"app/src/main/res/values{qual}/strings.xml")
    un = lambda s: s.replace("\\n", "\n").replace("\\'", "'").replace('\\"', '"')
    return {e.get("name"): un(e.text or "") for e in ET.parse(p).getroot().iter("string")}
S = strings("")
S.update(strings(QUAL[LANG]))

def adb(*a, check=False):
    return subprocess.run(ADB + list(a), capture_output=True, text=True, check=check).stdout

def sh(cmd):
    return adb("shell", cmd)

def sleep(s):
    time.sleep(s)

def dump():
    sh("uiautomator dump /sdcard/ui.xml")
    x = sh("cat /sdcard/ui.xml")
    root = ET.fromstring(x[x.index("<?xml"):] if "<?xml" in x else x)
    nodes = []
    for n in root.iter("node"):
        b = list(map(int, re.findall(r"\d+", n.get("bounds"))))
        nodes.append(dict(text=n.get("text") or "", desc=n.get("content-desc") or "", rid=n.get("resource-id") or "",
                          x=(b[0] + b[2]) // 2, y=(b[1] + b[3]) // 2, checkable=n.get("checkable") == "true",
                          checked=n.get("checked") == "true", top=b[1], bottom=b[3]))
    return nodes

def find(text, last=False, rid=None, must=True):
    """Node whose text (or content description) starts with `text`; last=True takes the last match."""
    hits = [n for n in dump() if (rid and rid in n["rid"]) or (text and (n["text"].startswith(text) or n["desc"].startswith(text)))]
    if not hits:
        if must:
            raise SystemExit(f"NOT FOUND on screen: {text or rid!r}")
        return None
    return hits[-1] if last else hits[0]

def tap(x, y, wait=1.5):
    sh(f"input tap {x} {y}"); sleep(wait)

def tap_text(text, wait=1.5, **kw):
    n = find(text, **kw); tap(n["x"], n["y"], wait=wait); return n

def switch_of(label):
    """(x, y, checked) of the switch in the same row as the text `label`."""
    nodes = dump()
    row = next((n for n in nodes if n["text"].startswith(label)), None)
    if row is None:
        return None
    sw = [n for n in nodes if n["checkable"] and abs(n["y"] - row["y"]) < 90]
    return (sw[0]["x"], sw[0]["y"], sw[0]["checked"]) if sw else None

def scroll_to(label, tries=8):
    for _ in range(tries):
        s = switch_of(label)
        if s and s[1] > 1500:    # too close to the gesture bar: bring the row to the middle first
            sh(f"input swipe 540 1700 540 {1700 - (s[1] - 1000)} 600"); sleep(1)   # never start in the gesture bar
            s = switch_of(label)
        if s:
            return s
        sh("input swipe 540 1700 540 900 400"); sleep(1)
    raise SystemExit(f"row not found: {label!r} (in front: {sh('dumpsys window | grep -m1 mCurrentFocus').strip()})")

def shot(name, wait=2.0):
    sleep(wait)
    with open(os.path.join(OUT, f"{name}.png"), "wb") as f:
        f.write(subprocess.run(ADB + ["exec-out", "screencap", "-p"], capture_output=True).stdout)
    print("shot", name)

def shake():
    for _ in range(3):
        adb("emu", "sensor", "set", "acceleration", "30:0:9.8"); sleep(0.15)
        adb("emu", "sensor", "set", "acceleration", "-30:0:9.8"); sleep(0.15)
    adb("emu", "sensor", "set", "acceleration", "0:9.8:0")

def off():
    """Dimming off by shaking (a user Off), checked through the window list; never through a dump."""
    for _ in range(3):
        if not overlay():
            return
        shake(); sleep(2)
    raise SystemExit("dimming did not turn off by shaking")

def overlay():
    return re.search(r"Window\{[0-9a-f]+ u0 com\.tim\.dimmer\}", sh("dumpsys window windows")) is not None

def bound():
    return any("label=Dimmer" in l for l in sh("dumpsys accessibility").splitlines() if "Bound services" in l)

def app():
    sh(f"am start -n {PKG}/.MainActivity"); sleep(2)

def home():
    sh("input keyevent HOME"); sleep(1)

def broadcast(action, *extras):
    sh(f"am broadcast -a com.plainapps.dimmer.action.{action} -p {PKG} " + " ".join(extras)); sleep(2.5)

def open_settings():
    """Settings from the top. The activity keeps Settings open across `am start`, so leave it first if it is showing."""
    for _ in range(3):
        app()
        for _ in range(5):       # Dimmer's home shows the "Presets" heading; Settings or the disclosure -> Back
            if "com.plainapps.dimmer/com.plainapps.dimmer.MainActivity" not in sh("dumpsys window | grep -m1 mCurrentFocus"):
                app(); continue  # not in front (a language or size change restarts the activity)
            if S["label_presets"] in {n["text"] for n in dump()}:
                break
            sh("input keyevent BACK"); sleep(1.5)
        n = find(S["label_settings"], must=False)
        if n:
            tap(n["x"], n["y"], wait=2)
            if S["label_backlight_section"] in {n["text"] for n in dump()}:
                return
    raise SystemExit("could not open Dimmer's Settings")

def set_switch(label, on):
    x, y, checked = scroll_to(label)
    if checked != on:
        tap(x, y)
    return checked

def demo(enter=True):
    if not enter:
        sh("am broadcast -a com.android.systemui.demo -e command exit"); return
    sh("settings put global sysui_demo_allowed 1")
    for c in ["enter", "clock -e hhmm 2230", "battery -e level 100 -e plugged false", "network -e wifi show -e level 4",
              "network -e mobile hide", "notifications -e visible false"]:
        sh(f"am broadcast -a com.android.systemui.demo -e command {c}")

def revoke():
    sh("am start -a android.settings.ACCESSIBILITY_SETTINGS"); sleep(2)
    n = next((n for n in dump() if n["text"] == "Dimmer" and "android:id/title" in n["rid"]), None)
    if n is None:
        raise SystemExit("Dimmer not in the Accessibility list")
    tap(n["x"], n["y"]); tap_text(None, rid="com.android.settings:id/main_switch_bar")
    tap_text(None, rid="android:id/button1")
    for _ in range(3):
        sh("input keyevent BACK"); sleep(0.6)

def grant():
    app(); tap_text(S["label_allow"], wait=2)
    sh("input swipe 540 1900 540 300 500"); sleep(1.2)
    tap_text(S["disclosure_agree"], wait=2.5)
    n = next((n for n in dump() if n["text"] == "Dimmer" and "android:id/title" in n["rid"]), None)
    if n is not None:      # the general list opened (the details deep link is refused for third parties, D-V3-40)
        tap(n["x"], n["y"], wait=2)
    tap_text(None, rid="com.android.settings:id/main_switch_bar", wait=2)
    tap_text(None, rid="accessibility_permission_enable_allow_button", wait=2)
    for _ in range(2):
        sh("input keyevent BACK"); sleep(0.8)
    for _ in range(5):
        if bound():
            return
        sleep(2)
    raise SystemExit("grant failed: service not bound")

# ---------------------------------------------------------------- run
if not bound():
    raise SystemExit("precondition: Accessibility access must be granted")
off()
if "--app-locale" in flags:
    sh(f"cmd locale set-app-locales {PKG} --user 0 --locales {LANG}"); sleep(2)
sh("wm size 1080x2160"); sleep(3)
demo()
for k in set(re.findall(r"key=(-1\|android\|[0-9]+\|null\|1000)", sh("dumpsys notification --noredact"))):
    sh(f"cmd notification snooze --for 86400000 '{k}'")          # "Serial console enabled" etc.

open_settings()
set_switch(S["label_automation"], True)
sh("input keyevent BACK"); sleep(1)
home(); app()

# 01 Reading, 02 Candlelight (dimming on through the automation broadcast; retry once after the 60 s user-off lockout)
broadcast("PRESET", "--es name reading")
if not overlay():
    print("automation locked out (recent user Off): waiting 62 s"); sleep(62); broadcast("PRESET", "--es name reading")
shot("01", 1.5)
broadcast("PRESET", "--es name candlelight"); shot("02", 1)
sh("am start -a android.intent.action.DIAL -d tel:5550123"); shot("03", 2.5)
sh("input keyevent BACK"); sleep(1); sh("cmd statusbar expand-notifications"); shot("04", 2)
sh("cmd statusbar collapse"); sleep(1); off()

# 05 Settings > Schedule (switching the schedule on dims at once inside the window; shake it off for the shot)
open_settings()
x, y, on = scroll_to(S["label_schedule_enabled"])
if not on:
    tap(x, y, wait=2.5); off()
open_settings()
sh("input swipe 540 1700 540 900 500"); sleep(1)
x, y, on = scroll_to(S["label_schedule_enabled"])     # dump while off: put the schedule row near the top
sh(f"input swipe 540 {y} 540 {max(y - 650, 330)} 600"); shot("05", 1.5)
if "--extra" in flags:                                    # every Settings page, top to bottom
    open_settings()
    previous = None
    for i in range(1, 15):                                # until the page stops moving
        sleep(1)
        png = subprocess.run(ADB + ["exec-out", "screencap", "-p"], capture_output=True).stdout
        if png == previous:
            break
        open(os.path.join(OUT, f"x{i:02d}-settings.png"), "wb").write(png); print("shot", f"x{i:02d}-settings")
        previous = png
        sh("input swipe 540 1800 540 600 500")
    sh("input keyevent BACK"); sleep(1)
sh("input keyevent BACK"); sleep(1.5)

# 06 preset menu (long-press Candlelight)
app()
n = find(S["preset_candlelight"], last=True)
sh(f"input swipe {n['x']} {n['y']} {n['x']} {n['y']} 900"); shot("06", 1.5)
sh("input keyevent BACK"); sleep(1)

# 07 home screen with the widget (on the AVD: second home page), dimming on from its power button
home(); sleep(1)
w = None
for _ in range(3):                       # the widget page: the first page that shows Dimmer's power button
    w = next((n for n in dump() if "widget_power" in n["rid"]), None)
    if w:
        break
    sh("input swipe 900 1000 150 1000 300"); sleep(1.5)
if w is None:
    raise SystemExit("Dimmer widget not found on the first three home pages")
tap(w["x"], w["y"], wait=0.5); shot("07", 2.5)
off(); home()

# automation back off before access is touched
open_settings(); set_switch(S["label_automation"], False); sh("input keyevent BACK"); sleep(1)   # the default

# 08 the disclosure (access revoked through Settings, then the real grant flow again)
revoke(); app()
tap_text(S["label_allow"]); shot("08", 1.5)
if "--extra" in flags:
    for i in range(1, 4):
        sh("input swipe 540 1700 540 700 500"); shot(f"x{20 + i}-disclosure", 1)
sh("input keyevent BACK"); sleep(1)
grant()

sh("wm size reset"); demo(False)
if "--app-locale" in flags:
    sh(f"cmd locale set-app-locales {PKG} --user 0")      # no --locales = empty list = follow the system again
home()
print("done:", OUT, "access bound" if bound() else "ACCESS NOT BOUND")

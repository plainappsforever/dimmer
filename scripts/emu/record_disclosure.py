#!/usr/bin/env python3
"""Re-records shots 2-4 of the Accessibility demo video (disclosure, DECLINE, CONSENT) on emulator-5570 (v3.1.1), so
the video always shows the disclosure text of the build under review. Shots 1 and 5-8 do not show the disclosure and
stay as they are (dist/play/demo-raw/A, E-H).

Usage: python3 scripts/emu/record_disclosure.py <prefix>     -> dist/play/demo-raw/<prefix>BC.mp4 (shots 2+3) and D.mp4
  then point dist/play/accessibility-demo-cutlist.json at the new files and run `swift scripts/assemble-demo.swift`.
Before: English UI, wm size reset (1080x2424), the RELEASE build installed as Google Play would install it
  (`adb install -r -i com.android.vending dist/dimmer-<v>-release.apk`, so the "Restricted setting" sentence is hidden
  exactly as for Play users), Accessibility access GRANTED, dimming OFF. Leaves access granted, dimming off.
Recording: `adb shell screenrecord --size 720x1616` (the same size as the other raw segments), stopped with SIGINT by
  its own device PID. All taps use coordinates measured in a dry run first, so no uiautomator dump runs while recording.
"""
import os, re, subprocess, sys, time, xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ADB = ["adb", "-s", "emulator-5570"]
PKG = "com.plainapps.dimmer"
PREFIX = sys.argv[1]
RAW = os.path.join(ROOT, "dist/play/demo-raw")

def sh(cmd):
    return subprocess.run(ADB + ["shell", cmd], capture_output=True, text=True).stdout

def sleep(s):
    time.sleep(s)

def dump():
    sh("uiautomator dump /sdcard/ui.xml")
    x = sh("cat /sdcard/ui.xml")
    root = ET.fromstring(x[x.index("<?xml"):])
    out = []
    for n in root.iter("node"):
        b = list(map(int, re.findall(r"\d+", n.get("bounds"))))
        out.append(dict(text=n.get("text") or "", rid=n.get("resource-id") or "", x=(b[0] + b[2]) // 2, y=(b[1] + b[3]) // 2))
    return out

def where(text=None, rid=None, last=False):
    hits = [n for n in dump() if (text and n["text"].startswith(text)) or (rid and rid in n["rid"])]
    if not hits:
        raise SystemExit(f"not on screen: {text or rid!r}")
    n = hits[-1] if last else hits[0]
    return n["x"], n["y"]

def tap(xy, wait=1.5):
    sh(f"input tap {xy[0]} {xy[1]}"); sleep(wait)

def bound():
    return any("label=Dimmer" in l for l in sh("dumpsys accessibility").splitlines() if "Bound services" in l)

def app():
    sh(f"am start -n {PKG}/.MainActivity"); sleep(2)

def back(n=1):
    for _ in range(n):
        sh("input keyevent BACK"); sleep(0.8)

SCROLL = "input swipe 540 1700 540 1250 1300"      # dry run: 450 px per step
HALF = "input swipe 540 1700 540 1475 1300"        # recording: half steps, slow and readable

def to_bottom_count():
    """How many SCROLL swipes until the two buttons are on screen (measured on the disclosure, dry run)."""
    for i in range(10):
        if any(n["text"] == "Not now" and n["y"] < 2250 for n in dump()):
            return i
        sh(SCROLL); sleep(1.2)
    raise SystemExit("disclosure bottom not reached")

def revoke():
    sh("am start -a android.settings.ACCESSIBILITY_SETTINGS"); sleep(2)
    n = next(n for n in dump() if n["text"] == "Dimmer" and "android:id/title" in n["rid"])
    tap((n["x"], n["y"]))
    tap(where(rid="com.android.settings:id/main_switch_bar"))
    tap(where(rid="android:id/button1"))
    back(3)

def record_start(name):
    sh(f"screenrecord --size 720x1616 --bit-rate 6000000 /sdcard/{name}.mp4 >/dev/null 2>&1 & echo $! > /sdcard/rec.pid")
    sleep(1.2)

def record_stop(name):
    sleep(0.8)
    sh("kill -INT $(cat /sdcard/rec.pid)"); sleep(2)
    dest = os.path.join(RAW, f"{name}.mp4")
    if os.path.exists(dest):
        raise SystemExit(f"{dest} exists; choose another prefix (raw recordings are never overwritten)")
    subprocess.run(ADB + ["pull", f"/sdcard/{name}.mp4", dest], capture_output=True, check=True)
    print("recorded", dest)

if not bound():
    raise SystemExit("precondition: access granted")
for k in set(re.findall(r"key=([^ ]+)", sh("dumpsys notification --noredact"))):
    if PKG not in k:
        sh(f"cmd notification snooze --for 86400000 '{k}'")

# ---- dry run: measure every coordinate (dumps allowed: nothing is recorded, dimming is off)
revoke(); app()
card = where("Set up access")
tap(card, 2)
swipes = to_bottom_count()
agree, not_now = where("Agree and open settings"), where("Not now")
tap(agree, 2.5)
n = next(n for n in dump() if n["text"] == "Dimmer" and "android:id/title" in n["rid"])
dimmer_row = (n["x"], n["y"]); tap(dimmer_row, 2)
switch = where(rid="com.android.settings:id/main_switch_bar"); tap(switch, 2)
allow = where(rid="accessibility_permission_enable_allow_button"); tap(allow, 2)
back(2)
if not bound():
    raise SystemExit("dry run: grant failed")
print("measured", dict(card=card, swipes=swipes, agree=agree, not_now=not_now, row=dimmer_row, switch=switch, allow=allow))
revoke(); app()
reading = where("Candlelight", last=True)   # a preset that is not active: the values visibly change, no layer appears

# ---- B + C in ONE recording: screenrecord writes no frames while the screen is still, so a pause at the very end of a
# file is lost; the DECLINE tap right after the bottom pause keeps it. The cut list splits the file at the printed time.
t0 = None
def mark(label):
    print(f"  {label}: {time.time() - t0:5.2f} s after recording start (approximate, check frames)")
record_start(f"{PREFIX}BC"); t0 = time.time() - 1.2
sleep(0.8); mark("tap Set up access"); tap(card, 4.5)            # the top of the disclosure, time to read
for _ in range(2 * swipes):
    sh(HALF); sleep(3.5)                                          # slow scroll in half steps, pauses to read
sleep(1.0)
mark("tap Not now (DECLINE)"); tap(not_now, 2.0)                  # C starts here
mark("tap Candlelight (values change, no layer)"); tap(reading, 2.5); sh(SCROLL.replace("1700 540 1250", "1500 540 1400")); sleep(1.5)   # a last small movement keeps the final pause in the file
record_stop(f"{PREFIX}BC")

# ---- D: CONSENT - Set up access, Agree, Use Dimmer, Allow, back to Dimmer
record_start(f"{PREFIX}D")
sleep(0.8); tap(card, 1.5)
for _ in range(swipes):
    sh("input swipe 540 1700 540 1250 400"); sleep(0.5)
sleep(0.8); tap(agree, 2.5)
tap(dimmer_row, 2.0)
tap(switch, 2.0)
tap(allow, 2.0)
back(2); sleep(1.5)
record_stop(f"{PREFIX}D")
print("access bound" if bound() else "ACCESS NOT BOUND")

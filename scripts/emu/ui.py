# Find or tap a UI element by regex over 'text|content-desc|resource-id' (uiautomator dump on emulator-5570).
# Usage: python3 scripts/emu/ui.py find|tap|switch '<regex>' [index]   (switch: x y checked of the row's switch)   - ONLY while dimming is OFF: a dump makes Android rebind
# accessibility services, which (by design) stops dimming (CLAUDE.md).
import subprocess, sys, re, xml.etree.ElementTree as ET
mode, pat = sys.argv[1], sys.argv[2]; idx = int(sys.argv[3]) if len(sys.argv) > 3 else 0
adb = ['adb', '-s', 'emulator-5570']
subprocess.run(adb + ['shell', 'uiautomator', 'dump', '/sdcard/ui.xml'], capture_output=True)
x = subprocess.run(adb + ['shell', 'cat', '/sdcard/ui.xml'], capture_output=True, text=True).stdout
root = ET.fromstring(x[x.index('<?xml'):] if '<?xml' in x else x)
hits = []
for n in root.iter('node'):
    label = (n.get('text') or '') + '|' + (n.get('content-desc') or '') + '|' + (n.get('resource-id') or '')
    if pat == '*' or re.search(pat, label, re.I):
        b = list(map(int, re.findall(r'\d+', n.get('bounds'))))
        hits.append((label, (b[0] + b[2]) // 2, (b[1] + b[3]) // 2, n.get('clickable'), n.get('checked')))
if mode == 'switch':   # the switch in the same row as the first match: prints "<x> <y> <checked>"
    if not hits: print('NOT FOUND', pat); sys.exit(1)
    y0 = hits[0][2]
    for n in root.iter('node'):
        if n.get('checkable') == 'true':
            b = list(map(int, re.findall(r'\d+', n.get('bounds')))); cy = (b[1] + b[3]) // 2
            if abs(cy - y0) < 80: print((b[0] + b[2]) // 2, cy, n.get('checked')); sys.exit(0)
    print('NO SWITCH', pat); sys.exit(1)
elif mode == 'find' or pat == '*':
    for h in hits: print(h)
elif hits:
    h = hits[idx]; subprocess.run(adb + ['shell', 'input', 'tap', str(h[1]), str(h[2])]); print('tapped', h)
else:
    print('NOT FOUND', pat); sys.exit(1)

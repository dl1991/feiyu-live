#!/usr/bin/env python3
"""Tap the first UI node whose content-desc/text matches. Usage: tap.py desc|text <value>"""
import re, subprocess, sys, time, xml.etree.ElementTree as ET
kind, value = sys.argv[1], sys.argv[2]
attr = "content-desc" if kind == "desc" else "text"
for attempt in range(4):
    subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
    xml = subprocess.run(["adb", "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True).stdout
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        time.sleep(2); continue
    for n in root.iter("node"):
        if n.get(attr, "").strip().lower() == value.lower():
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            subprocess.run(["adb", "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2)])
            print(f"tapped {attr}={value!r}")
            sys.exit(0)
    time.sleep(2)
print(f"not found: {attr}={value!r}")
sys.exit(1)

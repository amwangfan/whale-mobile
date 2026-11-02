#!/usr/bin/env python3
"""Minimal UI walker for adb-driven testing: dump hierarchy, tap node by desc/text."""
import re, subprocess, sys, time

ADB = ["/opt/android-sdk/platform-tools/adb", "-s", "100.64.85.85:5555"]

def sh(*args, timeout=60):
    return subprocess.run(ADB + list(args), capture_output=True, text=True, timeout=timeout).stdout

def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/window_dump.xml")
    out = subprocess.run(ADB + ["pull", "-q", "/sdcard/window_dump.xml", "/tmp/ui.xml"],
                         capture_output=True, text=True, timeout=30)
    return open("/tmp/ui.xml", encoding="utf-8", errors="replace").read()

def find_bounds(xml, needle):
    for m in re.finditer(r'<node[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*?>', xml):
        node = m.group(0)
        desc = re.search(r'content-desc="([^"]*)"', node)
        txt = re.search(r'text="([^"]*)"', node)
        if (desc and needle in desc.group(1)) or (txt and needle in txt.group(1)):
            x1, y1, x2, y2 = (int(m.group(i)) for i in range(1, 5))
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None

def tap(needle, settle=3):
    xml = dump()
    xy = find_bounds(xml, needle)
    if not xy:
        texts = sorted(set(re.findall(r'content-desc="([^"]{1,40})"', xml)) |
                       set(re.findall(r'text="([^"]{1,40})"', xml)))
        print(f"NOT FOUND: {needle!r}; visible:", [t for t in texts if t][:15])
        return False
    sh("shell", "input", "tap", str(xy[0]), str(xy[1]))
    time.sleep(settle)
    print(f"tapped {needle!r} at {xy}")
    return True

if __name__ == "__main__":
    for needle in sys.argv[1:]:
        if not tap(needle):
            break

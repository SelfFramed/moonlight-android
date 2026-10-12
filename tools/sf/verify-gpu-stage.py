"""End-to-end check of the GPU picture stage on an emulator or device.

Opens the test pattern activity, switches presets with key presses, takes screenshots and
compares the 16 gray steps with the values the original userscript math predicts
(app/src/test/resources/sf/curve-reference.json). Expects the factory presets in slots 1-6.

Usage: python tools/sf/verify-gpu-stage.py <adb serial> <applicationId> <out dir>
  e.g. python tools/sf/verify-gpu-stage.py emulator-5580 com.selfframed.moonlight.debug build/sf-gpu-test

Emulator hints (docs/selfframed/TESTPROTOKOLL.md): headless, low priority, and run
`adb shell settings put secure immersive_mode_confirmations confirmed` once; otherwise the
full-screen hint dims the first screenshots.
Last result: 12.10.2026, API 36 emulator (SwiftShader), 7 presets x 16 steps, max deviation 0.
"""
import json
import os
import subprocess
import sys
import time
import zlib
import struct

_SDK = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
ADB = os.path.join(_SDK, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
FIXTURE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "test", "resources",
                       "sf", "curve-reference.json")
def adb(serial, *args, binary=False, check=True):
    r = subprocess.run([ADB, "-s", serial, *args], capture_output=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr.decode(errors='replace')}")
    return r.stdout if binary else r.stdout.decode(errors="replace")


def decode_png(data):
    """Minimal PNG decoder (8-bit RGB/RGBA, non-interlaced) -> (w, h, rows of (r,g,b))."""
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos, idat, w = 8, b"", None
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        ctype = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        if ctype == b"IHDR":
            w, h, depth, color, _, _, interlace = struct.unpack(">IIBBBBB", body)
            assert depth == 8 and color in (2, 6) and interlace == 0, (depth, color, interlace)
            bpp = 3 if color == 2 else 4
        elif ctype == b"IDAT":
            idat += body
        pos += 12 + length
    raw = zlib.decompress(idat)
    stride = w * bpp
    rows, prev = [], bytearray(stride)
    i = 0
    for _ in range(h):
        f = raw[i]
        line = bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1:
                line[x] = (line[x] + a) & 0xFF
            elif f == 2:
                line[x] = (line[x] + b) & 0xFF
            elif f == 3:
                line[x] = (line[x] + ((a + b) >> 1)) & 0xFF
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 0xFF
        rows.append([(line[x], line[x + 1], line[x + 2]) for x in range(0, stride, bpp)])
        prev = line
    return w, h, rows


def screenshot(serial, path):
    data = adb(serial, "exec-out", "screencap", "-p", binary=True)
    with open(path, "wb") as f:
        f.write(data)
    return decode_png(data)


def find_steps(rows, w, h):
    """Find a row through the 16 gray steps of the neutral pattern; return (y, x centres)."""
    min_run = w // 16 // 2
    for y in range(0, h // 2, 2):
        row = rows[y]
        runs, start = [], 0
        for x in range(1, w + 1):
            if x == w or max(abs(row[x][k] - row[start][k]) for k in range(3)) > 1:
                if x - start >= min_run:
                    runs.append((start, x, row[start]))
                start = x
        grays = [r for r in runs if r[2][0] == r[2][1] == r[2][2]]
        # Steps: values round(i*255/15) for i = 0..15, the first one black
        values = [g[2][0] for g in grays]
        expected = [round(i * 255 / 15) for i in range(16)]
        for k in range(len(values) - 15):
            if all(abs(values[k + i] - expected[i]) <= 1 for i in range(16)):
                steps = grays[k:k + 16]
                return y + 4, [(s[0] + s[1]) // 2 for s in steps]
    raise RuntimeError("gray steps not found in neutral screenshot")


def main():
    serial, app_id, out = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(out, exist_ok=True)
    fixture = {p["preset"]["name"]: p for p in json.load(open(FIXTURE, encoding="utf-8"))["presets"]}

    adb(serial, "shell", "am", "force-stop", app_id)
    adb(serial, "logcat", "-c")
    adb(serial, "shell", "am", "start", "-W", "-n",
        f"{app_id}/com.limelight.selfframed.grading.GradingTestPatternActivity")
    time.sleep(4)

    # Start from neutral, whatever was stored before
    adb(serial, "shell", "input", "keyevent", "KEYCODE_0")
    time.sleep(2.5)
    w, h, rows = screenshot(serial, os.path.join(out, "00-neutral.png"))
    y, xs = find_steps(rows, w, h)
    print(f"screen {w}x{h}, steps found at y={y}")

    # slot key -> fixture preset
    cases = [("KEYCODE_3", "paper-white"), ("KEYCODE_6", "inverted"), ("KEYCODE_2", "lift-shadows"),
             ("KEYCODE_5", "threshold"), ("KEYCODE_1", "film-game"), ("KEYCODE_4", "high-contrast"),
             ("KEYCODE_0", "neutral")]
    worst_total = 0
    failures = 0
    for key, name in cases:
        adb(serial, "shell", "input", "keyevent", key)
        time.sleep(2.5)
        _, _, rows = screenshot(serial, os.path.join(out, f"{key[-1]}-{name}.png"))
        lut = fixture[name]["lut"]["r"]
        worst, line = 0, []
        for i, x in enumerate(xs):
            v = round(i * 255 / 15)
            expected = round(lut[v] * 255)
            got = rows[y][x]
            dev = max(abs(c - expected) for c in got)
            worst = max(worst, dev)
            line.append(f"{v}->{got[0]}({expected})")
        status = "OK " if worst <= 2 else "BAD"
        failures += worst > 2
        worst_total = max(worst_total, worst)
        print(f"{status} {name:14s} max dev {worst}: " + " ".join(line))

    sf = [l for l in adb(serial, "logcat", "-d", check=False).splitlines() if "SelfFramed" in l or "FATAL" in l]
    with open(os.path.join(out, "logcat-selfframed.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(sf))
    print("\n".join(sf[-12:]))
    print(f"RESULT: {'PASS' if failures == 0 else 'FAIL'} (worst deviation {worst_total})")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

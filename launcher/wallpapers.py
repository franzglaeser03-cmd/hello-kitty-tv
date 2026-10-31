#!/usr/bin/env python3
"""Draws the glass style's wallpapers: soft glows of color on a dark base, darker toward the
bottom where the tiles sit. Standard library only. A little noise keeps the dark gradients from
banding on a TV. They are the launcher's own, so there is nothing to license or download.

Run: python3 launcher/wallpapers.py OUT_DIR
Push: adb push OUT_DIR/. /sdcard/Android/data/tv.dumbtv.home/files/wallpapers/
"""
import math
from pathlib import Path
import random
import struct
import sys
import zlib

W, H = 960, 540

# name, base color, then glows as (x, y, width, height, red, green, blue), in fractions of the screen
SCENES = {
    "1-tide": ((6, 12, 34), [
        (0.20, 0.10, 0.42, 0.45, 30, 110, 255),
        (0.72, 0.18, 0.38, 0.40, 110, 70, 255),
        (0.95, 0.62, 0.30, 0.42, 0, 190, 235),
        (0.08, 0.75, 0.32, 0.36, 170, 50, 200),
        (0.50, 0.50, 0.22, 0.22, 60, 170, 255),
    ]),
    "2-ember": ((20, 8, 30), [
        (0.15, 0.20, 0.40, 0.42, 245, 90, 60),
        (0.70, 0.08, 0.42, 0.40, 215, 50, 150),
        (0.92, 0.55, 0.32, 0.40, 255, 150, 60),
        (0.30, 0.78, 0.36, 0.34, 110, 40, 200),
    ]),
    "3-aurora": ((4, 22, 28), [
        (0.25, 0.12, 0.44, 0.40, 20, 215, 160),
        (0.75, 0.25, 0.36, 0.42, 40, 130, 235),
        (0.55, 0.05, 0.24, 0.22, 150, 240, 190),
        (0.10, 0.78, 0.30, 0.32, 110, 60, 215),
        (0.95, 0.80, 0.26, 0.30, 0, 170, 150),
    ]),
}


def chunk(kind, data):
    body = kind + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))


def draw(base, glows, seed):
    noise = random.Random(seed)
    # A glow is a product of a curve across and a curve down, so both can be worked out once.
    across = [[math.exp(-(((x / (W - 1) - g[0]) / g[2]) ** 2)) for x in range(W)] for g in glows]
    down = [[math.exp(-(((y / (H - 1) - g[1]) / g[3]) ** 2)) for y in range(H)] for g in glows]
    raw = bytearray()
    for y in range(H):
        raw.append(0)
        shade = 1.0 - 0.55 * max(0.0, (y / (H - 1) - 0.35) / 0.65) ** 1.3
        for x in range(W):
            r, g, b = base
            for i, glow in enumerate(glows):
                k = across[i][x] * down[i][y]
                r += (glow[4]) * k
                g += (glow[5]) * k
                b += (glow[6]) * k
            d = noise.random() + noise.random() - 1.0
            raw.append(max(0, min(255, int(r * shade + d + 0.5))))
            raw.append(max(0, min(255, int(g * shade + d + 0.5))))
            raw.append(max(0, min(255, int(b * shade + d + 0.5))))
    header = struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for n, (name, (base, glows)) in enumerate(SCENES.items()):
        path = out / f"glass-{name}.png"
        path.write_bytes(draw(base, glows, n))
        print(path, path.stat().st_size // 1024, "KB")


if __name__ == "__main__":
    main()

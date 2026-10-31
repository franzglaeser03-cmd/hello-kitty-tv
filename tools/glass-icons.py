#!/usr/bin/env python3
"""Turns app icons into cut-out glass glyphs, all in the Apple TV logo's color.

The color is read from the Apple TV logo itself: its pearly white with soft pink, lilac, mint and
yellow. Every other icon loses its own colors and takes that field, with what was lighter or darker
in the original kept as depth. Then each gets a light from above, a bevel along its edges and a
soft shadow so it floats above the glass tile.

Needs Pillow and numpy. The icons are their owners' trademarks, so none are in this repository:
fetch them with tools/fetch-icons.py for your own TV, and keep the results off the internet.

Run: python3 tools/glass-icons.py SOURCE_DIR OUT_DIR
Push: adb push OUT_DIR/. /sdcard/Android/data/tv.dumbtv.home/files/icons/
"""
from pathlib import Path
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter
import numpy as np

SRC, OUT = Path(sys.argv[1]), Path(sys.argv[2])
OUT.mkdir(parents=True, exist_ok=True)
SIZE = 360
PAD = 30  # room for the shadow and bevel
WORK = 720
APPLE = "com.apple.atve.amazon.appletv"


def smooth(a, lo, hi):
    t = np.clip((a - lo) / (hi - lo), 0, 1)
    return t * t * (3 - 2 * t)


def plate_mask(inset):
    """An iOS icon's rounded square, shrunk by {inset} of the side; keeps out rim and corners."""
    big = Image.new("L", (WORK * 2, WORK * 2), 0)
    a = round(inset * WORK * 2)
    ImageDraw.Draw(big).rounded_rectangle(
        (a, a, WORK * 2 - a, WORK * 2 - a), radius=round(0.2 * (WORK * 2 - 2 * a)), fill=255)
    return np.asarray(big.resize((WORK, WORK), Image.LANCZOS)).astype(np.float32) / 255


def cutout(path, mode, lo=0.10, hi=0.30):
    """Color and alpha with the background gone."""
    im = np.asarray(Image.open(path).convert("RGB").resize((WORK, WORK), Image.LANCZOS)).astype(np.float32)
    if mode == "white":
        # For icons whose background has a glow or pictures in it: only near-white is the logo.
        low = im.min(axis=2) / 255
        return im, smooth(low, 0.62, 0.86) * plate_mask(0.05)
    # The background, read just inside where a glass rim would be, row by row. In the rows where
    # a rounded corner cuts the column off, the nearest row clear of the corner stands in.
    x0, width = round(0.075 * WORK), 16
    y0, y1 = round(0.24 * WORK), round(0.76 * WORK)
    rows = np.clip(np.arange(WORK), y0, y1)
    left = im[rows][:, x0:x0 + width].mean(axis=1)
    right = im[rows][:, WORK - x0 - width:WORK - x0].mean(axis=1)
    kernel = np.ones(21) / 21
    for c in range(3):
        left[:, c] = np.convolve(np.pad(left[:, c], 10, mode="edge"), kernel, mode="valid")
        right[:, c] = np.convolve(np.pad(right[:, c], 10, mode="edge"), kernel, mode="valid")
    fx = np.linspace(0, 1, WORK)[None, :, None]
    bg = left[:, None, :] * (1 - fx) + right[:, None, :] * fx
    diff = np.abs(im - bg).max(axis=2) / 255
    alpha = smooth(diff, lo, hi) * plate_mask(0.055)
    safe = np.maximum(alpha, 0.02)[..., None]
    return np.clip(bg + (im - bg) / safe, 0, 255), alpha


def apple_field():
    """The Apple TV logo's colors, smeared into a field that covers a whole icon."""
    color, alpha = cutout(SRC / f"{APPLE}.jpg", "key", 0.06, 0.2)
    ys, xs = np.where(alpha > 0.3)
    box = (xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)
    weight = Image.fromarray((alpha * 255).astype(np.uint8)).crop(box)
    rgb = Image.fromarray(color.astype(np.uint8)).crop(box)
    premult = np.asarray(rgb).astype(np.float32) * (np.asarray(weight).astype(np.float32)[..., None] / 255)
    radius = max(weight.size) * 0.07
    # A blur of color over a blur of coverage: fills the gaps between the letters with the color
    # around them, and leaves no dark halo.
    channels = [np.asarray(Image.fromarray(premult[..., c].astype(np.uint8)).filter(ImageFilter.GaussianBlur(radius))).astype(np.float32)
                for c in range(3)]
    cover = np.asarray(weight.filter(ImageFilter.GaussianBlur(radius))).astype(np.float32) / 255
    field = np.dstack(channels) / np.maximum(cover[..., None], 0.02)
    # Keeps the shimmer visible once the light and bevel have gone over it.
    gray = field.mean(axis=2, keepdims=True)
    field = gray + (field - gray) * 1.75
    field = field * (252 / max(1.0, np.percentile(field, 98)))
    field = np.clip(field, 150, 255)
    return Image.fromarray(field.astype(np.uint8)).resize((256, 256), Image.BICUBIC)


FIELD = apple_field()


def glass(color, alpha, own_color=False, flat=False):
    """The Apple TV color, depth from the original, light, bevel and shadow."""
    ys, xs = np.where(alpha > 0.25)
    box = (xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)
    rgb = Image.fromarray(color.astype(np.uint8)).crop(box)
    mask = Image.fromarray((alpha * 255).astype(np.uint8)).crop(box)
    room = SIZE - 2 * PAD
    k = room / max(rgb.size)
    size = (max(1, round(rgb.size[0] * k)), max(1, round(rgb.size[1] * k)))
    rgb = rgb.resize(size, Image.LANCZOS)
    mask = mask.resize(size, Image.LANCZOS)
    at = ((SIZE - size[0]) // 2, (SIZE - size[1]) // 2)

    source = np.asarray(rgb).astype(np.float32)
    inside = np.asarray(mask).astype(np.float32) / 255
    luma = (source * [0.299, 0.587, 0.114]).sum(axis=2) / 255
    seen = luma[inside > 0.6] if (inside > 0.6).any() else luma.ravel()
    lo_l, hi_l = np.percentile(seen, 4), np.percentile(seen, 96)
    # What was lighter in the logo stays solid and what was darker sinks back, so a shape inside a
    # shape, like the 2 in ZDF's circle, still reads once the colors are gone.
    if hi_l - lo_l > 0.12:
        depth = np.clip((luma - lo_l) / (hi_l - lo_l), 0, 1)
    else:
        depth = np.ones_like(luma)
    tinted = np.asarray(FIELD.resize(size, Image.BICUBIC)).astype(np.float32)
    if own_color:
        tinted = source
        solid = np.ones_like(depth)
    elif flat:
        solid = np.ones_like(depth)
    else:
        tinted = tinted * (0.84 + 0.16 * depth)[..., None]
        solid = 0.70 + 0.30 * depth

    base = Image.new("RGB", (SIZE, SIZE), (255, 255, 255))
    base.paste(Image.fromarray(np.clip(tinted, 0, 255).astype(np.uint8)), at)
    a_img = Image.new("L", (SIZE, SIZE), 0)
    a_img.paste(Image.fromarray(np.clip(inside * solid * 255, 0, 255).astype(np.uint8)), at)
    shape = Image.new("L", (SIZE, SIZE), 0)
    shape.paste(mask, at)

    c = np.asarray(base).astype(np.float32)
    m = np.asarray(a_img).astype(np.float32) / 255
    rows = np.arange(SIZE)[:, None]
    top, bottom = at[1], at[1] + size[1]
    t = np.clip((rows - top) / max(1, bottom - top), 0, 1)
    # Light from above, fading down the glyph, and shade at its foot.
    c = c + (255 - c) * np.clip(0.34 - 0.8 * t, 0, 1)[..., None]
    c = c * (1 - np.clip((t - 0.58) * 0.34, 0, 0.16))[..., None]
    # A bevel: the edge facing the light brightens, the one away from it darkens.
    soft = shape.filter(ImageFilter.GaussianBlur(3.0))
    s = np.asarray(shape).astype(np.float32) / 255
    down = np.asarray(ImageChops.offset(soft, 2, 3)).astype(np.float32) / 255
    up = np.asarray(ImageChops.offset(soft, -2, -3)).astype(np.float32) / 255
    bright = np.clip(s - down, 0, 1)[..., None]
    dark = np.clip(s - up, 0, 1)[..., None]
    c = c + (255 - c) * np.clip(bright * 2.2, 0, 0.95)
    c = c * (1 - np.clip(dark * 0.95, 0, 0.42))
    # The bevel holds the edge even where the shape sinks back.
    m = np.maximum(m, np.clip(bright[..., 0] * 2.0, 0, 0.9) * s)

    glyph = np.dstack([np.clip(c, 0, 255), m * 255 * 0.97]).astype(np.uint8)
    top_layer = Image.fromarray(glyph, "RGBA")
    shadow_a = ImageChops.offset(shape, 0, 7).filter(ImageFilter.GaussianBlur(8)).point(lambda v: int(v * 0.55))
    shadow = Image.new("RGBA", (SIZE, SIZE), (24, 6, 16, 0))
    shadow.putalpha(shadow_a)
    return Image.alpha_composite(shadow, top_layer)


# How to find each logo: "white" for a white logo on a busy background, otherwise tighter or looser
# keying for the icons whose background is close to the logo.
RULES = {
    APPLE: ("key", 0.06, 0.2),
    "com.disney.disneyplus": ("white", 0, 0),
    "tv.arte.plus7": ("white", 0, 0),
    "com.hbo.hbonow": ("key", 0.14, 0.34),
    "com.netflix.ninja": ("key", 0.10, 0.26),
    "com.spotify.tv.android": ("key", 0.14, 0.34),
}

for path in sorted(SRC.glob("*.jpg")):
    mode, lo, hi = RULES.get(path.stem, ("key", 0.10, 0.30))
    color, alpha = cutout(path, mode, lo, hi)
    glass(color, alpha, own_color=path.stem == APPLE, flat=mode == "white").save(OUT / f"{path.stem}.png", optimize=True)
    print(path.stem, (OUT / f"{path.stem}.png").stat().st_size // 1024, "KB")


def built_in(name, draw_it):
    """The launcher's own two tiles, drawn large and scaled down so the edges stay clean."""
    big = Image.new("L", (WORK * 2, WORK * 2), 0)
    draw_it(ImageDraw.Draw(big))
    glyph = np.asarray(big.resize((WORK, WORK), Image.LANCZOS)).astype(np.float32) / 255
    color = np.full((WORK, WORK, 3), 255, dtype=np.float32)
    glass(color, glyph).save(OUT / f"{name}.png", optimize=True)
    print(name)


def tv(d):
    d.rounded_rectangle((180, 300, 1260, 1000), radius=70, fill=255)
    d.rounded_rectangle((250, 370, 1190, 930), radius=30, fill=0)
    d.rounded_rectangle((520, 1040, 920, 1110), radius=30, fill=255)


def cast(d):
    d.rounded_rectangle((200, 280, 1240, 1000), radius=70, fill=255)
    d.rounded_rectangle((270, 350, 1170, 930), radius=30, fill=0)
    d.rectangle((150, 620, 700, 1100), fill=0)
    for r, width in ((420, 70), (270, 70)):
        d.arc((240 - r, 1060 - r, 240 + r, 1060 + r), 270, 360, fill=255, width=width)
    d.ellipse((190, 1010, 290, 1110), fill=255)


def appstore(d):
    # Amazon's Appstore has no iOS icon to start from: a plain grid of app squares, the last one a plus.
    for x, y in ((260, 260), (780, 260), (260, 780)):
        d.rounded_rectangle((x, y, x + 400, y + 400), radius=90, fill=255)
    d.rounded_rectangle((950, 780, 1010, 1180), radius=30, fill=255)
    d.rounded_rectangle((780, 950, 1180, 1010), radius=30, fill=255)


def wifi(d):
    import math
    cx, cy = 720, 1120
    for r, w in ((780, 130), (530, 130), (280, 130)):
        d.arc((cx - r, cy - r, cx + r, cy + r), 225, 315, fill=255, width=w)
        # Rounded ends, as the system's own Wi-Fi symbol has.
        mid = r - w / 2
        for deg in (225, 315):
            x = cx + mid * math.cos(math.radians(deg))
            y = cy + mid * math.sin(math.radians(deg))
            d.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=255)
    d.ellipse((cx - 95, cy - 95, cx + 95, cy + 95), fill=255)


built_in("dumbtv.livetv", tv)
built_in("dumbtv.cast", cast)
built_in("com.amazon.venezia", appstore)
built_in("dumbtv.wifi", wifi)

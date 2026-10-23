#!/usr/bin/env python3
"""Validate the Play Store assets in store/ against Play Console requirements.

Run:  python3 store/tools/verify_assets.py
Exits non-zero if any asset would be rejected or is missing.
"""

from __future__ import annotations

import os
import sys
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
STORE = os.path.join(ROOT, "store")
RES = os.path.join(ROOT, "app", "src", "main", "res")

errors: list[str] = []
checks = 0


def check(condition: bool, message: str):
    global checks
    checks += 1
    if not condition:
        errors.append(message)


def png_info(path: str):
    im = Image.open(path)
    return im.size, im.mode


# ------------------------------------------------------------------- graphics

icon_path = os.path.join(STORE, "graphics", "app-icon-512.png")
check(os.path.isfile(icon_path), f"missing {icon_path}")
if os.path.isfile(icon_path):
    size, mode = png_info(icon_path)
    check(size == (512, 512), f"app icon must be 512x512, got {size}")
    check(mode == "RGBA", f"app icon must be 32-bit PNG (RGBA), got {mode}")
    if mode == "RGBA":
        alpha = Image.open(icon_path).getchannel("A")
        check(alpha.getextrema() == (255, 255), "app icon alpha channel must be fully opaque")
    check(Image.open(icon_path).format == "PNG", "app icon must be a PNG")

feature_path = os.path.join(STORE, "graphics", "feature-graphic-1024x500.png")
check(os.path.isfile(feature_path), f"missing {feature_path}")
if os.path.isfile(feature_path):
    size, mode = png_info(feature_path)
    check(size == (1024, 500), f"feature graphic must be 1024x500, got {size}")
    check(mode == "RGB", f"feature graphic must be 24-bit PNG without alpha, got {mode}")
    check(Image.open(feature_path).format == "PNG", "feature graphic must be a PNG")

# ---------------------------------------------------------------- screenshots

shots_dir = os.path.join(STORE, "screenshots", "phone")
shots = sorted(
    os.path.join(shots_dir, f) for f in os.listdir(shots_dir) if f.endswith(".png")
) if os.path.isdir(shots_dir) else []
check(2 <= len(shots) <= 8, f"need 2-8 phone screenshots, found {len(shots)}")
for path in shots:
    im = Image.open(path)
    w, h = im.size
    name = os.path.relpath(path, ROOT)
    check(im.format == "PNG", f"{name}: must be PNG")
    check(im.mode == "RGB", f"{name}: must be 24-bit PNG without alpha, got {im.mode}")
    check(320 <= min(w, h), f"{name}: min side 320px, got {min(w, h)}")
    check(max(w, h) <= 3840, f"{name}: max side 3840px, got {max(w, h)}")
    check(max(w, h) / min(w, h) <= 2.0 + 1e-6, f"{name}: aspect ratio must be at most 2:1")

# ------------------------------------------------------------------- launcher

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for density, expected in DENSITIES.items():
    for name, want_alpha in (("ic_launcher.png", False), ("ic_launcher_round.png", True)):
        path = os.path.join(RES, f"mipmap-{density}", name)
        check(os.path.isfile(path), f"missing legacy launcher icon {path}")
        if not os.path.isfile(path):
            continue
        size, mode = png_info(path)
        check(size == (expected, expected), f"{path}: expected {expected}x{expected}, got {size}")
        if want_alpha:
            check(mode == "RGBA", f"{path}: round icon should keep transparency (RGBA), got {mode}")
            if mode == "RGBA":
                corners = Image.open(path).getchannel("A")
                check(
                    corners.getpixel((0, 0)) == 0,
                    f"{path}: round icon corners must be transparent",
                )

# -------------------------------------------------------------------- listing

listing = os.path.join(STORE, "listing", "en-US")


def read_text(name: str) -> str:
    path = os.path.join(listing, name)
    check(os.path.isfile(path), f"missing listing file {os.path.relpath(path, ROOT)}")
    if not os.path.isfile(path):
        return ""
    with open(path, encoding="utf-8") as fh:
        return fh.read().strip()


title = read_text("title.txt")
short = read_text("short_description.txt")
full = read_text("full_description.txt")
notes = read_text("release_notes.txt")

check(0 < len(title) <= 30, f"title must be 1-30 characters, got {len(title)}")
check(0 < len(short) <= 80, f"short description must be 1-80 characters, got {len(short)}")
check(0 < len(full) <= 4000, f"full description must be 1-4000 characters, got {len(full)}")
check(len(notes) <= 500, f"release notes must be at most 500 characters, got {len(notes)}")

# ---------------------------------------------------------------------- report

print(f"Checked {checks} constraints across the Play Store assets.")
if errors:
    print(f"\n{len(errors)} problem(s):")
    for err in errors:
        print(f"  - {err}")
    sys.exit(1)

print("All Play Store assets pass the checks:")
print("  app icon          512x512   (RGBA, opaque)")
print("  feature graphic   1024x500 (24-bit PNG)")
print(f"  phone screenshots {len(shots)} x 1080x1920 (24-bit PNG, 9:16)")
print("  legacy launcher icons for mdpi..xxxhdpi")
print(f"  listing text      title {len(title)}/30, short {len(short)}/80, full {len(full)}/4000")

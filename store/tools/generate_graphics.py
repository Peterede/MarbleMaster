#!/usr/bin/env python3
"""Generate the Play Store graphics and legacy launcher icons for Swing.

Everything is drawn procedurally with Pillow so the art can be regenerated and
tweaked without any design tool. Colours come from the in-game palette
(GameView.kt) so store art matches the game.

Outputs
    store/graphics/app-icon-512.png          512x512   (Play listing icon)
    store/graphics/feature-graphic-1024x500.png
    app/src/main/res/mipmap-*/ic_launcher.png       (API 24-25 legacy icons)
    app/src/main/res/mipmap-*/ic_launcher_round.png

Usage:  python3 store/tools/generate_graphics.py
"""

from __future__ import annotations

import math
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT_GRAPHICS = os.path.join(ROOT, "store", "graphics")
OUT_RES = os.path.join(ROOT, "app", "src", "main", "res")

FONT_BOLD = "/usr/share/fonts/truetype/ubuntu/Ubuntu-B.ttf"
FONT_REG = "/usr/share/fonts/truetype/ubuntu/Ubuntu-R.ttf"
FONT_MED = "/usr/share/fonts/truetype/ubuntu/Ubuntu-M.ttf"

# In-game marble palette (fill, dark rim) - see MARBLE_COLORS in GameView.kt.
MARBLES = {
    "red": ("#EF5350", "#B71C1C"),
    "blue": ("#42A5F5", "#0D47A1"),
    "yellow": ("#FFCA28", "#F57F17"),
    "green": ("#66BB6A", "#1B5E20"),
    "purple": ("#AB47BC", "#4A148C"),
    "orange": ("#FF8A65", "#BF360C"),
}

BG_TOP = (27, 34, 51)
BG_BOTTOM = (9, 12, 18)
CYAN = "#4FC3F7"


# ----------------------------------------------------------------- primitives


def hex_rgb(value: str) -> tuple[int, int, int]:
    value = value.lstrip("#")
    return tuple(int(value[i : i + 2], 16) for i in (0, 2, 4))


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def vertical_gradient(size, top, bottom) -> Image.Image:
    w, h = size
    img = Image.new("RGB", (1, h))
    px = img.load()
    for y in range(h):
        px[0, y] = mix(top, bottom, y / max(1, h - 1))
    return img.resize((w, h), Image.BILINEAR)


def radial_glow(size, center, radius, color, peak_alpha) -> Image.Image:
    """Soft round glow as an RGBA layer."""
    w, h = size
    mask = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(mask)
    steps = 48
    for i in range(steps, 0, -1):
        t = i / steps
        alpha = round(peak_alpha * (1 - t) ** 2)
        r = radius * t
        d.ellipse([center[0] - r, center[1] - r, center[0] + r, center[1] + r], fill=alpha)
    mask = mask.filter(ImageFilter.GaussianBlur(radius / 12))
    layer = Image.new("RGBA", (w, h), color + (0,))
    layer.putalpha(mask)
    return layer


def draw_marble(canvas, cx, cy, r, color_key, ss=4):
    """Glossy marble with a radial shade, rim light and specular highlight."""
    base_hex, dark_hex = MARBLES[color_key]
    base, dark = hex_rgb(base_hex), hex_rgb(dark_hex)

    R = max(8, int(round(r * ss)))
    tile = Image.new("RGBA", (2 * R, 2 * R), (0, 0, 0, 0))
    d = ImageDraw.Draw(tile)
    ccx = ccy = float(R)
    steps = 90
    for i in range(steps, 0, -1):
        t = i / steps
        rad = R * t
        if t > 0.9:
            col = mix(base, dark, (t - 0.9) / 0.1)
        else:
            col = mix(mix(base, (255, 255, 255), 0.30), base, min(1.0, t / 0.75))
        d.ellipse([ccx - rad, ccy - rad, ccx + rad, ccy + rad], fill=col + (255,))

    # rim light along the lower-right edge
    ring = Image.new("RGBA", tile.size, (0, 0, 0, 0))
    rd = ImageDraw.Draw(ring)
    inset = R * 0.06
    rd.arc(
        [ccx - R + inset, ccy - R + inset, ccx + R - inset, ccy + R - inset],
        start=20,
        end=150,
        fill=mix(base, (255, 255, 255), 0.55) + (140,),
        width=max(1, int(R * 0.07)),
    )
    ring = ring.filter(ImageFilter.GaussianBlur(max(0.5, R * 0.03)))
    tile = Image.alpha_composite(tile, ring)

    # specular highlight
    hi = Image.new("RGBA", tile.size, (0, 0, 0, 0))
    hd = ImageDraw.Draw(hi)
    hx, hy = ccx - R * 0.34, ccy - R * 0.38
    hr = R * 0.26
    hd.ellipse([hx - hr, hy - hr * 0.78, hx + hr, hy + hr * 0.78], fill=(255, 255, 255, 225))
    hi = hi.filter(ImageFilter.GaussianBlur(max(0.5, R * 0.09)))
    tile = Image.alpha_composite(tile, hi)

    out = max(2, int(round(2 * r)))
    tile = tile.resize((out, out), Image.LANCZOS)
    canvas.alpha_composite(tile, (int(round(cx - r)), int(round(cy - r))))


def rounded_line(draw, p0, p1, width, fill):
    draw.line([p0, p1], fill=fill, width=max(1, int(width)))
    rr = width / 2
    for p in (p0, p1):
        draw.ellipse([p[0] - rr, p[1] - rr, p[0] + rr, p[1] + rr], fill=fill)


def star(draw, cx, cy, r, fill, points=4):
    pts = []
    for i in range(points * 2):
        ang = -math.pi / 2 + i * math.pi / points
        rad = r if i % 2 == 0 else r * 0.34
        pts.append((cx + math.cos(ang) * rad, cy + math.sin(ang) * rad))
    draw.polygon(pts, fill=fill)


def draw_spaced_text(draw, xy, text, font, fill, tracking=0):
    x, y = xy
    for ch in text:
        draw.text((x, y), ch, font=font, fill=fill)
        x += draw.textlength(ch, font=font) + tracking
    return x


def text_width(draw, text, font, tracking=0):
    return sum(draw.textlength(ch, font=font) for ch in text) + tracking * max(0, len(text) - 1)


# ------------------------------------------------------------------- scale art


def draw_scale_scene(canvas, ox, oy, s, tilt=1.0, flying=True):
    """Balance scale with three marbles on the heavy pan and one on the light pan.

    (ox, oy) is the pivot position in canvas pixels and s scales the drawing.
    The heavy (left) pan hangs lower, the light (right) pan rides high.
    """
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)

    half = 150 * s
    drop = 30 * s * tilt

    left = (ox - half, oy + drop)
    right = (ox + half, oy - drop)

    heavy_pan = (left[0], left[1] + 78 * s)
    light_pan = (right[0], right[1] + 78 * s)

    # strings first, so the pans cover their ends
    string_col = (226, 233, 242, 150)
    for beam_pt, pan_c, rx in ((left, heavy_pan, 66), (right, light_pan, 60)):
        for side in (-1, 1):
            rounded_line(
                d,
                beam_pt,
                (pan_c[0] + side * rx * s * 0.86, pan_c[1] - 6 * s),
                max(1.5, 3.2 * s),
                string_col,
            )

    # soft drop shadow under the whole scale
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    rounded_line(sd, left, right, 22 * s, (0, 0, 0, 160))
    for pan_c, rx in ((heavy_pan, 66), (light_pan, 60)):
        sd.ellipse(
            [
                pan_c[0] - rx * s,
                pan_c[1] - 16 * s + 14 * s,
                pan_c[0] + rx * s,
                pan_c[1] + 16 * s + 14 * s,
            ],
            fill=(0, 0, 0, 170),
        )
    shadow = shadow.filter(ImageFilter.GaussianBlur(12 * s))
    canvas.alpha_composite(shadow)

    # stand + base
    rounded_line(d, (ox, oy + 6 * s), (ox, oy + 118 * s), 15 * s, "#6C7B89")
    rounded_line(d, (ox, oy + 6 * s), (ox, oy + 118 * s), 6 * s, "#8A99A6")
    d.rounded_rectangle(
        [ox - 64 * s, oy + 112 * s, ox + 64 * s, oy + 138 * s], radius=13 * s, fill="#5A6874"
    )
    d.rounded_rectangle(
        [ox - 64 * s, oy + 112 * s, ox + 64 * s, oy + 123 * s], radius=10 * s, fill="#76858F"
    )

    # beam
    rounded_line(d, left, right, 20 * s, "#4E5A66")
    rounded_line(d, left, right, 15 * s, "#EDF2F8")

    # pans
    for pan_c, rx in ((heavy_pan, 66), (light_pan, 60)):
        sheen = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        shd = ImageDraw.Draw(sheen)
        shd.ellipse(
            [pan_c[0] - rx * s, pan_c[1] - 17 * s, pan_c[0] + rx * s, pan_c[1] + 17 * s],
            fill="#8D6E63",
        )
        sheen = sheen.filter(ImageFilter.GaussianBlur(6 * s))
        canvas.alpha_composite(sheen)
        d.ellipse(
            [pan_c[0] - rx * s, pan_c[1] - 15 * s, pan_c[0] + rx * s, pan_c[1] + 15 * s],
            fill="#2B211C",
            outline="#8D6E63",
            width=max(2, int(6 * s)),
        )

    # marbles: three on the heavy pan, one on the light pan
    for dx, dy, key, r in (
        (-30, -12, "red", 27),
        (0, -24, "yellow", 28),
        (30, -12, "blue", 27),
    ):
        draw_marble(
            canvas,
            heavy_pan[0] + dx * s,
            heavy_pan[1] + dy * s,
            r * s,
            key,
        )

    draw_marble(
        canvas, light_pan[0], light_pan[1] - 18 * s, 25 * s, "green"
    )

    # pivot cap last so it sits above the beam
    d.ellipse([ox - 16 * s, oy - 16 * s, ox + 16 * s, oy + 16 * s], fill="#A9B7C4")
    d.ellipse([ox - 8 * s, oy - 8 * s, ox + 8 * s, oy + 8 * s], fill="#5E6C79")

    if flying:
        trail = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        td = ImageDraw.Draw(trail)
        td.arc(
            [light_pan[0] - 30 * s, light_pan[1] - 230 * s, light_pan[0] + 170 * s, light_pan[1] + 10 * s],
            start=198,
            end=332,
            fill=(255, 255, 255, 100),
            width=max(2, int(3 * s)),
        )
        trail = trail.filter(ImageFilter.GaussianBlur(1.2 * s))
        canvas.alpha_composite(trail)
        draw_marble(canvas, light_pan[0] + 44 * s, light_pan[1] - 152 * s, 23 * s, "purple")
        draw_marble(canvas, light_pan[0] + 112 * s, light_pan[1] - 92 * s, 16 * s, "orange")
        star(d, light_pan[0] + 12 * s, light_pan[1] - 112 * s, 11 * s, "#FFD54F")
        star(d, light_pan[0] + 102 * s, light_pan[1] - 176 * s, 8 * s, "#E1F5FE")
        star(d, light_pan[0] + 124 * s, light_pan[1] - 34 * s, 6 * s, "#FFD54F")

    canvas.alpha_composite(layer)


# ----------------------------------------------------------------------- icon


def make_icon(size=512) -> Image.Image:
    ss = 2
    S = size * ss
    img = vertical_gradient((S, S), BG_TOP, BG_BOTTOM).convert("RGBA")
    img.alpha_composite(radial_glow((S, S), (S * 0.5, S * 0.42), S * 0.62, (36, 52, 82), 190))

    # faint board grid so the icon echoes the playfield
    grid = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(grid)
    step = S // 8
    for i in range(1, 8):
        gd.line([(i * step, 0), (i * step, S)], fill=(120, 145, 180, 18), width=max(1, ss))
        gd.line([(0, i * step), (S, i * step)], fill=(120, 145, 180, 18), width=max(1, ss))
    img.alpha_composite(grid)

    draw_scale_scene(img, S * 0.47, S * 0.505, S / 512 * 0.90, tilt=1.0, flying=True)
    return img.resize((size, size), Image.LANCZOS).convert("RGB")


# ------------------------------------------------------------ feature graphic


def make_feature_graphic(w=1024, h=500) -> Image.Image:
    ss = 2
    W, H = w * ss, h * ss
    img = vertical_gradient((W, H), (18, 24, 36), (8, 11, 16)).convert("RGBA")
    img.alpha_composite(radial_glow((W, H), (W * 0.74, H * 0.46), W * 0.55, (30, 62, 100), 210))
    img.alpha_composite(radial_glow((W, H), (W * 0.12, H * 0.1), W * 0.36, (18, 40, 62), 120))

    d = ImageDraw.Draw(img)
    title = ImageFont.truetype(FONT_BOLD, int(124 * ss))
    sub = ImageFont.truetype(FONT_MED, int(27 * ss))
    body = ImageFont.truetype(FONT_REG, int(21 * ss))
    tag = ImageFont.truetype(FONT_BOLD, int(17 * ss))

    # Keep everything inside the middle 80% so no store surface crops the copy.
    left = 96 * ss
    draw_spaced_text(d, (left, 96 * ss), "SWING", title, (255, 255, 255), tracking=7 * ss)
    d.rectangle([left + 3 * ss, 240 * ss, left + 100 * ss, 247 * ss], fill=hex_rgb(CYAN))
    draw_spaced_text(
        d, (left + 3 * ss, 266 * ss), "MARBLE SCALE PUZZLE", sub, hex_rgb(CYAN), tracking=4 * ss
    )
    d.text(
        (left + 2 * ss, 316 * ss),
        "Tip the scales. Chain the combos.",
        font=body,
        fill=(176, 190, 202),
    )

    # feature chips
    chips = ["LOCAL HIGH SCORES", "CHIPTUNE AUDIO"]
    x = left + 2 * ss
    for text in chips:
        tw = d.textlength(text, font=tag)
        d.rounded_rectangle(
            [x, 358 * ss, x + tw + 26 * ss, 392 * ss],
            radius=17 * ss,
            outline=(79, 195, 247, 120),
            width=max(1, int(1.4 * ss)),
            fill=(30, 52, 74, 90),
        )
        d.text((x + 13 * ss, 364 * ss), text, font=tag, fill=(207, 232, 246))
        x += tw + 32 * ss

    draw_scale_scene(img, W * 0.725, H * 0.50, (W / 1024) * 0.84, tilt=1.0, flying=True)

    return img.resize((w, h), Image.LANCZOS).convert("RGB")


# ------------------------------------------------------------ launcher mipmaps


def make_legacy_icon(size: int, round_icon: bool) -> Image.Image:
    """Square (or circular) launcher icon for API 24-25.

    The adaptive icon (mipmap-anydpi-v26) uses the vector art; these PNGs are
    the fallback for devices that predate adaptive icons.
    """
    ss = 2
    S = size * ss
    art = make_icon(S)

    if round_icon:
        inset = int(S * 0.02)
        mask = Image.new("L", (S, S), 0)
        ImageDraw.Draw(mask).ellipse([inset, inset, S - 1 - inset, S - 1 - inset], fill=255)
        out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        out.paste(art, (0, 0), mask)
        return out.resize((size, size), Image.LANCZOS)

    return art.resize((size, size), Image.LANCZOS)


DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def main():
    os.makedirs(OUT_GRAPHICS, exist_ok=True)

    # Play asks for a 512x512 32-bit PNG; keep the alpha channel fully opaque.
    icon = make_icon(512).convert("RGBA")
    icon.save(os.path.join(OUT_GRAPHICS, "app-icon-512.png"), "PNG", optimize=True)

    feature = make_feature_graphic(1024, 500)
    feature.save(os.path.join(OUT_GRAPHICS, "feature-graphic-1024x500.png"), "PNG", optimize=True)

    for density, size in DENSITIES.items():
        folder = os.path.join(OUT_RES, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        make_legacy_icon(size, round_icon=False).save(
            os.path.join(folder, "ic_launcher.png"), "PNG", optimize=True
        )
        make_legacy_icon(size, round_icon=True).save(
            os.path.join(folder, "ic_launcher_round.png"), "PNG", optimize=True
        )

    for path in (
        os.path.join(OUT_GRAPHICS, "app-icon-512.png"),
        os.path.join(OUT_GRAPHICS, "feature-graphic-1024x500.png"),
    ):
        im = Image.open(path)
        print(f"{os.path.relpath(path, ROOT)}: {im.size[0]}x{im.size[1]} {im.mode}")


if __name__ == "__main__":
    main()

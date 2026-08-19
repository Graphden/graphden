#!/usr/bin/env python3
"""Regenerate the text-bearing Graphden social banners (docs/brand).

The wordmark is DejaVu Sans Bold, the tagline/footer are DejaVu Sans Mono
(both stock Debian fonts). The lambda-mark geometry comes from
docs/brand/README.md (viewBox 32: nodes r3 @ (9,6)(25,26)(8,26), edges
(9,6)->(25,26) and (17,16)->(8,26), stroke 3, round caps).

Outputs: gd-banner-boosty.png (1500x500, dark), gd-banner-youtube.png
(2560x1440, light), gd-social-preview.png (1280x640, dark).
"""
from PIL import Image, ImageDraw, ImageFont, ImageFilter

BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
MONO = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"

TAGLINE = "Software your whole team can see — and your AI can safely change."

INK = (13, 17, 23)          # #0D1117
BLUE = (0, 102, 204)        # #0066CC
BLUE_LIGHT = (77, 148, 255) # #4D94FF
MUTED = (152, 162, 182)     # #98a2b6
MUTED_DARKER = (90, 100, 120)
LIGHT_TEXT = (245, 247, 250)
DARK_TEXT = (16, 20, 28)


def rounded_tile(size):
    """Brand-blue rounded-square tile with the white lambda mark."""
    s = 4  # supersample
    S = size * s
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=BLUE + (255,))
    # lambda mark: brand geometry in viewBox 32, drawn inside a centered 60% box
    u = S * 0.60 / 32.0
    ox = oy = S * 0.20
    p = lambda x, y: (ox + x * u, oy + y * u)
    w = max(2, int(3 * u))
    for a, b in [((9, 6), (25, 26)), ((17, 16), (8, 26))]:
        d.line([p(*a), p(*b)], fill="white", width=w)
        for pt in (a, b):
            x, y = p(*pt)
            d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill="white")
    for n in [(9, 6), (25, 26), (8, 26)]:
        x, y = p(*n)
        r = 3 * u
        d.ellipse([x - r, y - r, x + r, y + r], fill="white")
    return im.resize((size, size), Image.LANCZOS)


def backdrop(draw, nodes, edges, color, node_alpha, edge_alpha, scale=1.0, dx=0, dy=0):
    """Ambient node-graph constellation."""
    pt = lambda i: (nodes[i][0] * scale + dx, nodes[i][1] * scale + dy)
    for a, b in edges:
        draw.line([pt(a), pt(b)], fill=color + (edge_alpha,), width=max(2, int(2 * scale)))
    for i, (x, y, r) in enumerate(nodes):
        x, y = x * scale + dx, y * scale + dy
        r = r * scale
        draw.ellipse([x - r, y - r, x + r, y + r], fill=color + (node_alpha,))


# One fixed constellation, reused at different scales/offsets per banner.
CONST_NODES = [(60, 40, 9), (300, 90, 11), (150, 190, 13), (90, 330, 9),
               (330, 370, 11), (520, 240, 13), (660, 130, 15), (630, 330, 10),
               (830, 210, 13), (960, 90, 11), (1090, 190, 11), (1150, 350, 8)]
CONST_EDGES = [(0, 1), (0, 2), (1, 2), (2, 3), (2, 5), (3, 4), (4, 5), (5, 6),
               (6, 8), (7, 8), (8, 9), (9, 10), (10, 11)]


def glow(im, cx, cy, radius, color, alpha):
    layer = Image.new("RGBA", im.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.ellipse([cx - radius, cy - radius, cx + radius, cy + radius], fill=color + (alpha,))
    layer = layer.filter(ImageFilter.GaussianBlur(radius / 2))
    im.alpha_composite(layer)


def wordmark(draw, x, y, size, graph_color, den_color, tracking=-0.02):
    f = ImageFont.truetype(BOLD, size)
    cx = x
    for ch, col in [(c, graph_color) for c in "graph"] + [(c, den_color) for c in "den"]:
        draw.text((cx, y), ch, font=f, fill=col)
        cx += draw.textlength(ch, font=f) + size * tracking
    return cx


def light_gradient(w, h):
    im = Image.new("RGB", (w, h))
    top, bot = (238, 242, 250), (225, 233, 246)
    for yy in range(h):
        t = yy / h
        im.paste(tuple(int(a + (b - a) * t) for a, b in zip(top, bot)), [0, yy, w, yy + 1])
    return im.convert("RGBA")


def dark_banner(w, h, tile_size, tile_xy, wm_size, wm_dxy, tag_size, tag_y,
                footer, footer_y, out):
    im = Image.new("RGBA", (w, h), INK + (255,))
    d = ImageDraw.Draw(im, "RGBA")
    backdrop(d, CONST_NODES, CONST_EDGES, (60, 90, 140), 130, 70,
             scale=w / 2700, dx=w * 0.64, dy=h * 0.04)
    tx, ty = tile_xy
    glow(im, tx + tile_size / 2, ty + tile_size / 2, tile_size * 1.1, (120, 170, 255), 26)
    im.alpha_composite(rounded_tile(tile_size), (tx, ty))
    d = ImageDraw.Draw(im, "RGBA")
    wordmark(d, tx + tile_size + wm_dxy[0], ty + wm_dxy[1], wm_size,
             LIGHT_TEXT, BLUE_LIGHT)
    fm = ImageFont.truetype(MONO, tag_size)
    d.text((tx, tag_y), TAGLINE, font=fm, fill=MUTED)
    if footer:
        ff = ImageFont.truetype(MONO, int(tag_size * 0.72))
        d.text((tx, footer_y), footer, font=ff, fill=MUTED_DARKER)
    im.convert("RGB").save(out)
    print(out)


def boosty(out):
    """Boosty cover, 1500x500 — but Boosty DISPLAYS it as a ~5.9:1 strip
    (only the middle band, roughly y 120..390, is visible on desktop) and
    overlays the round avatar on the bottom-left. So: no footer, all
    content inside the middle band, and x < ~400 left clear."""
    w, h = 1500, 500
    im = Image.new("RGBA", (w, h), INK + (255,))
    d = ImageDraw.Draw(im, "RGBA")
    backdrop(d, CONST_NODES, CONST_EDGES, (60, 90, 140), 110, 55,
             scale=0.42, dx=1030, dy=40)
    tile = 140
    tx, ty = 420, 155
    glow(im, tx + tile / 2, ty + tile / 2, tile * 1.1, (120, 170, 255), 26)
    im.alpha_composite(rounded_tile(tile), (tx, ty))
    d = ImageDraw.Draw(im, "RGBA")
    wordmark(d, tx + tile + 34, ty - 2, 105, LIGHT_TEXT, BLUE_LIGHT)
    fm = ImageFont.truetype(MONO, 23)
    d.text((tx + 2, 325), TAGLINE, font=fm, fill=MUTED)
    im.convert("RGB").save(out)
    print(out)


def youtube(out):
    w, h = 2560, 1440
    im = light_gradient(w, h)
    d = ImageDraw.Draw(im, "RGBA")
    backdrop(d, CONST_NODES, CONST_EDGES, (150, 180, 225), 130, 80,
             scale=1.05, dx=w * 0.60, dy=h * 0.02)
    backdrop(d, CONST_NODES, CONST_EDGES, (150, 180, 225), 90, 55,
             scale=0.9, dx=w * 0.58, dy=h * 0.62)
    # YouTube safe area: the centered 1546x423 box (x 507..2053) is all
    # that every device shows — tile, wordmark AND the tagline's end must
    # sit inside it (tagline at 31px ends at ~1998).
    tile = 175
    tx, ty = 530, (h - tile) // 2
    im.alpha_composite(rounded_tile(tile), (tx, ty))
    d = ImageDraw.Draw(im, "RGBA")
    wm_size = 120
    wordmark(d, tx + tile + 55, ty - 14, wm_size, DARK_TEXT, BLUE)
    fm = ImageFont.truetype(MONO, 31)
    d.text((tx + tile + 58, ty + tile - 26), TAGLINE, font=fm, fill=(105, 115, 132))
    im.convert("RGB").save(out)
    print(out)


def org_profile(dark, out):
    """GitHub org profile banner (Graphden/.github profile/assets/) —
    tile + wordmark with the tagline directly under the wordmark, in a
    dark and a light colorway for the two <picture> sources."""
    w, h = 2400, 760
    if dark:
        im = Image.new("RGBA", (w, h), INK + (255,))
        bd_color, na, ea = (60, 90, 140), 120, 60
        graph_c, den_c, tag_c = LIGHT_TEXT, BLUE_LIGHT, MUTED
    else:
        im = light_gradient(w, h)
        bd_color, na, ea = (150, 180, 225), 110, 65
        graph_c, den_c, tag_c = DARK_TEXT, BLUE, (105, 115, 132)
    d = ImageDraw.Draw(im, "RGBA")
    backdrop(d, CONST_NODES, CONST_EDGES, bd_color, na, ea,
             scale=0.78, dx=w * 0.625, dy=h * 0.10)
    tile = 350
    tx, ty = 145, (h - tile) // 2
    if dark:
        glow(im, tx + tile / 2, ty + tile / 2, tile * 1.05, (120, 170, 255), 22)
    im.alpha_composite(rounded_tile(tile), (tx, ty))
    d = ImageDraw.Draw(im, "RGBA")
    wx = tx + tile + 90
    wordmark(d, wx, ty - 20, 190, graph_c, den_c)
    fm = ImageFont.truetype(MONO, 38)
    d.text((wx + 6, ty + tile - 82), TAGLINE, font=fm, fill=tag_c)
    im.convert("RGB").save(out)
    print(out)


if __name__ == "__main__":
    import sys
    outdir = sys.argv[1] if len(sys.argv) > 1 else "."
    boosty(f"{outdir}/gd-banner-boosty.png")
    dark_banner(1280, 640, 150, (96, 200), 118, (36, -4), 24, 420,
                "graphden.dev", 560,
                f"{outdir}/gd-social-preview.png")
    youtube(f"{outdir}/gd-banner-youtube.png")
    org_profile(True, f"{outdir}/gd-org-banner-dark.png")
    org_profile(False, f"{outdir}/gd-org-banner-light.png")

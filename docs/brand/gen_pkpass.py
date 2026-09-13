#!/usr/bin/env python3
"""Build the Graphden Wallet card (.pkpass) — a generic pass whose QR is
the project URL.

The mark, wordmark and palette come from gen_banners.py, so the card is the
same identity as the banners (Ink ground, blue tile, white lambda).

    python3 docs/brand/gen_pkpass.py docs/brand/out            # bundle only
    python3 docs/brand/gen_pkpass.py docs/brand/out --sign \
        --team ABCDE12345 --pass-type pass.dev.graphden.card \
        --cert pass.pem --key key.pem --wwdr AppleWWDRCAG4.pem

Signing needs a Pass Type ID certificate from an Apple Developer account —
Wallet refuses an unsigned pass. Without --sign the script still writes the
complete bundle plus manifest.json, so signing later is one openssl call.
"""
import argparse
import hashlib
import importlib.util
import json
import pathlib
import subprocess
import sys
import zipfile

from PIL import Image, ImageDraw, ImageFont

HERE = pathlib.Path(__file__).resolve().parent

# gen_banners.py is imported by path; don't leave a __pycache__ in docs/.
sys.dont_write_bytecode = True

_spec = importlib.util.spec_from_file_location("gen_banners", HERE / "gen_banners.py")
brand = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(brand)

URL = "https://graphden.dev"
SERIAL = "graphden-card-1"

# Images the pass bundle carries, at the sizes Wallet expects.
ICONS = {"icon.png": 29, "icon@2x.png": 58, "icon@3x.png": 87}
THUMBS = {"thumbnail.png": 90, "thumbnail@2x.png": 180, "thumbnail@3x.png": 270}
# Wallet caps the logo at 160x50 pt, so the wordmark alone goes there and the
# lambda tile rides in the icon + thumbnail slots.
LOGO_BOX = {"logo.png": 1, "logo@2x.png": 2, "logo@3x.png": 3}
LOGO_PT = (160, 50)


def logo(scale):
    """White `graphden` wordmark fitted into Wallet's logo box, transparent ground."""
    box = (LOGO_PT[0] * scale, LOGO_PT[1] * scale)
    probe = ImageDraw.Draw(Image.new("RGBA", (1, 1)))

    def width_at(size):
        f = ImageFont.truetype(brand.BOLD, size)
        return sum(probe.textlength(c, font=f) for c in "graphden") - size * 0.02 * 7

    size = box[1]
    while size > 4 and width_at(size) > box[0] - 2 * scale:
        size -= 1
    im = Image.new("RGBA", box, (0, 0, 0, 0))
    d = ImageDraw.Draw(im, "RGBA")
    brand.wordmark(d, 1 * scale, (box[1] - size) / 2 - size * 0.14, size,
                   brand.LIGHT_TEXT, brand.BLUE_LIGHT)
    return im


def build_images(out: pathlib.Path):
    for name, size in {**ICONS, **THUMBS}.items():
        brand.rounded_tile(size).save(out / name)
    for name, scale in LOGO_BOX.items():
        logo(scale).save(out / name)


def pass_json(team, pass_type):
    return {
        "formatVersion": 1,
        "passTypeIdentifier": pass_type,
        "teamIdentifier": team,
        "serialNumber": SERIAL,
        "organizationName": "Graphden",
        "description": "Graphden — project card",
        "logoText": "",
        "backgroundColor": "rgb(13,17,23)",
        "foregroundColor": "rgb(255,255,255)",
        "labelColor": "rgb(77,148,255)",
        "sharingProhibited": False,
        "barcodes": [{
            "format": "PKBarcodeFormatQR",
            "message": URL,
            "messageEncoding": "iso-8859-1",
            "altText": "graphden.dev",
        }],
        "generic": {
            "primaryFields": [
                {"key": "site", "label": "PROJECT", "value": "graphden.dev"},
            ],
            "secondaryFields": [
                {"key": "what", "label": "WHAT IT IS",
                 "value": "Visual functional programming — code lives as a graph"},
            ],
            "backFields": [
                {"key": "tagline", "label": "Graphden", "value": brand.TAGLINE},
                {"key": "site-link", "label": "Site",
                 "value": f"<a href=\"{URL}\">graphden.dev</a>"},
                {"key": "docs", "label": "Docs",
                 "value": "<a href=\"https://graphden.dev/tutorial\">graphden.dev/tutorial</a>"},
                {"key": "code", "label": "Code",
                 "value": "<a href=\"https://github.com/Graphden\">github.com/Graphden</a>"},
            ],
        },
    }


def manifest(out: pathlib.Path):
    m = {p.name: hashlib.sha1(p.read_bytes()).hexdigest()
         for p in sorted(out.iterdir()) if p.name not in ("manifest.json", "signature")}
    (out / "manifest.json").write_text(json.dumps(m, indent=2) + "\n")
    return m


def sign(out: pathlib.Path, cert, key, wwdr, password):
    cmd = ["openssl", "smime", "-binary", "-sign",
           "-certfile", wwdr, "-signer", cert, "-inkey", key,
           "-in", str(out / "manifest.json"), "-out", str(out / "signature"),
           "-outform", "DER"]
    if password is not None:
        cmd += ["-passin", f"pass:{password}"]
    subprocess.run(cmd, check=True)


def package(out: pathlib.Path, dest: pathlib.Path):
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted(out.iterdir()):
            z.write(p, p.name)
    return dest


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("outdir")
    ap.add_argument("--team", default="TEAMIDXXXX")
    ap.add_argument("--pass-type", default="pass.dev.graphden.card")
    ap.add_argument("--sign", action="store_true")
    ap.add_argument("--cert")
    ap.add_argument("--key")
    ap.add_argument("--wwdr")
    ap.add_argument("--password")
    a = ap.parse_args()

    out = pathlib.Path(a.outdir)
    out.mkdir(parents=True, exist_ok=True)
    for stale in out.iterdir():
        stale.unlink()

    build_images(out)
    (out / "pass.json").write_text(
        json.dumps(pass_json(a.team, a.pass_type), indent=2, ensure_ascii=False) + "\n")
    manifest(out)

    if a.sign:
        missing = [f for f in ("cert", "key", "wwdr") if not getattr(a, f)]
        if missing:
            ap.error("--sign needs " + ", ".join("--" + m for m in missing))
        sign(out, a.cert, a.key, a.wwdr, a.password)
        dest = package(out, out.with_suffix(".pkpass"))
        print(f"signed pass: {dest}")
    else:
        print(f"unsigned bundle: {out}  ({len(list(out.iterdir()))} files)")
        print("sign it, then zip the directory contents (not the directory) as .pkpass")


if __name__ == "__main__":
    main()

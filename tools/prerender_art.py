#!/usr/bin/env python3
"""Pre-render every BM art thumbnail into the Zodiac phosphor treatment.

Run this **before the event, while there is internet**. It fetches the art feed,
downloads each thumbnail, applies the "D3" treatment (vector edges, purple where
the piece is lit, CRT scanlines) and writes the result into the APK's assets.

Why bake it in rather than style at draw time:

* ``RuntimeShader`` is API 33+ and the passenger Fires are API 28, so a
  real-time effect was never available on the hardware this is for.
* The playa has no reliable internet. An asset in the APK cannot fail to load,
  need a cache, or arrive half-downloaded at the moment someone looks at it.
* The treatment can then cost whatever it likes — this runs once on a laptop,
  not sixty times a second on a tablet.

The trade is that changing the look means re-running this and shipping a new
APK. That is the right way round: the images essentially never change, and the
tablets must work with no network at all.

    python3 tools/prerender_art.py --key $BM_API_KEY --year 2026

Output: ``app/src/main/assets/art/<uid>.webp``, plus ``index.json`` listing what
was rendered and what was skipped, so a missing image is explainable later.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.request

import numpy as np
from PIL import Image, ImageFilter

GREEN = np.array([0x00, 0xFF, 0x66], float)
PURPLE = np.array([0xC7, 0x7D, 0xFF], float)
DIM = np.array([0x2C, 0x8A, 0x4A], float)

OUT_W, OUT_H = 480, 348

# WebP, not PNG. The treatment is glow gradients over black, which PNG stores
# badly — 179 KB a piece, ~61 MB for the feed. WebP q80 holds the thin bright
# lines essentially indistinguishably at a third of that (measured against
# lossless side by side before choosing).
WEBP_QUALITY = 80

# A scanned line drawing is near-white and has no colour at all. This is the one
# document shape that separates cleanly from photographs — measured across the
# 2026 feed, everything else (spec sheets, renders) overlaps real photos on both
# luma and saturation, so no wider rule is honest. Selfies and crowds are
# deliberately NOT filtered: the treatment renders people as hollow wireframe
# figures, which is exactly how the DRIVER HUD draws them.
CAD_MEDIAN_LUMA = 0.97
CAD_SATURATION = 0.02


def fetch_json(url: str, key: str):
    req = urllib.request.Request(url, headers={"X-API-Key": key})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode())


def cover(im: Image.Image, w: int, h: int) -> Image.Image:
    scale = max(w / im.width, h / im.height)
    im = im.resize((max(1, int(im.width * scale)), max(1, int(im.height * scale))), Image.LANCZOS)
    left, top = (im.width - w) // 2, (im.height - h) // 2
    return im.crop((left, top, left + w, top + h))


def stats(im: Image.Image):
    a = np.asarray(im, float) / 255.0
    y = 0.2126 * a[..., 0] + 0.7152 * a[..., 1] + 0.0722 * a[..., 2]
    return float(np.median(y)), float(np.mean(a.max(2) - a.min(2)))


def normalised_luma(im: Image.Image) -> np.ndarray:
    a = np.asarray(im, float) / 255.0
    y = 0.2126 * a[..., 0] + 0.7152 * a[..., 1] + 0.0722 * a[..., 2]
    # A bright-background shot has to become glow-on-black: the cockpit is
    # black, and a white slab breaks the aesthetic instantly.
    if np.median(y) > 0.5:
        y = 1.0 - y
    lo, hi = np.percentile(y, 2), np.percentile(y, 98)
    return np.clip((y - lo) / max(hi - lo, 1e-6), 0, 1)


def treat(im: Image.Image) -> Image.Image:
    """D3: green vector edges, purple where the piece is actually lit, scanlines."""
    y = normalised_luma(im)
    smoothed = Image.fromarray((y * 255).astype(np.uint8)).filter(ImageFilter.SMOOTH_MORE)
    edges = np.clip((np.asarray(smoothed.filter(ImageFilter.FIND_EDGES), float) / 255.0 - 0.08) * 5.0, 0, 1)

    # Purple tracks the artwork's own light. Purple means "live value" everywhere
    # else in the cockpit, so letting the piece's glow drive it keeps the colour
    # meaningful rather than decorative.
    lit = np.clip((y - 0.72) * 3.2, 0, 1)
    lit_glow = np.asarray(
        Image.fromarray((lit * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(6)), float
    ) / 255.0
    edge_glow = np.asarray(
        Image.fromarray((edges * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(3)), float
    ) / 255.0

    out = (
        GREEN * edges[..., None]
        + DIM * edge_glow[..., None] * 0.9
        + PURPLE * lit_glow[..., None] * 0.85
        + PURPLE * lit[..., None] * 0.55
    )
    rows = np.ones(out.shape[0])
    rows[::3] = 0.45
    out = out * rows[:, None, None]
    return Image.fromarray(np.clip(out, 0, 255).astype(np.uint8))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--key", required=True, help="BM API key (see local.properties)")
    parser.add_argument("--year", type=int, default=2026)
    parser.add_argument("--out", default="app/src/main/assets/art")
    parser.add_argument("--base", default="https://api.burningman.org")
    parser.add_argument("--limit", type=int, default=0, help="stop after N (for a dry run)")
    args = parser.parse_args(argv)

    records = fetch_json(f"{args.base}/api/art?year={args.year}", args.key)
    os.makedirs(args.out, exist_ok=True)
    rendered, skipped = [], []

    for i, record in enumerate(records):
        if args.limit and len(rendered) >= args.limit:
            break
        uid = record.get("uid")
        images = record.get("images") or []
        url = images[0].get("thumbnail_url") if images else None
        name = record.get("name", "?")
        if not uid or not url:
            skipped.append({"uid": uid, "name": name, "why": "no thumbnail in feed"})
            continue
        try:
            with urllib.request.urlopen(url, timeout=60) as resp:
                raw = resp.read()
            src = Image.open(__import__("io").BytesIO(raw)).convert("RGB")
        except Exception as exc:  # noqa: BLE001 - one bad image must not stop the run
            skipped.append({"uid": uid, "name": name, "why": f"download failed: {exc}"})
            continue

        median, saturation = stats(src)
        if median >= CAD_MEDIAN_LUMA and saturation <= CAD_SATURATION:
            # A scanned CAD elevation carries no sense of the piece; the
            # description serves the passenger better than line noise.
            skipped.append({"uid": uid, "name": name, "why": "line drawing, not a photo"})
            continue

        treat(cover(src, OUT_W, OUT_H)).save(
            os.path.join(args.out, f"{uid}.webp"), "WEBP", quality=WEBP_QUALITY
        )
        rendered.append({"uid": uid, "name": name})
        print(f"[{i + 1}/{len(records)}] {name[:48]}", file=sys.stderr)

    index = {"year": args.year, "rendered": rendered, "skipped": skipped}
    with open(os.path.join(args.out, "index.json"), "w") as handle:
        json.dump(index, handle, indent=1)

    total = sum(
        os.path.getsize(os.path.join(args.out, f)) for f in os.listdir(args.out) if f.endswith(".webp")
    )
    print(
        f"\nrendered {len(rendered)}, skipped {len(skipped)}, {total / 1e6:.1f} MB of assets",
        file=sys.stderr,
    )
    for s in skipped:
        print(f"  skipped: {s['name'][:44]:46s} {s['why']}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

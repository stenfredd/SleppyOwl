#!/usr/bin/env python3
"""
Builds the app's logo resources from the source picture design/logo/sleepyowl.png (owl over the "SleepyOwl"
wordmark on a flat navy background, 1254×1254):

  drawable-xhdpi/banner.png               Android TV banner, 320×180 dp: owl + wordmark on the logo's navy
  drawable-xxxhdpi/ic_launcher_owl.png    adaptive icon foreground (108 dp), background is @color/ic_launcher_background
  drawable-xhdpi/logo_owl.png             owl alone, transparent — side rail, login, settings
  drawable-xhdpi/logo_wordmark.png        "SleepyOwl" alone, transparent

The navy is keyed out ("color to alpha"): the glow stays as translucent amber, so the owl and the wordmark sit on the
app's own dark background without a square. The owl's body is made opaque so it does not take the colour of what is
behind it. The in-app pictures are made at the TV's density (xhdpi, 2 px per dp) and at the size they are drawn
(see ui/components/Brand.kt), so they are not resampled on the TV.

Usage:
  python3 tools/logo/make_assets.py [--src design/logo/sleepyowl.png] [--res app/src/main/res] [--preview DIR]
"""
import argparse
import os

import numpy as np
from PIL import Image
from scipy import ndimage

BG = np.array([5, 9, 39]) / 255  # the logo's background
DEAD_ZONE = 7 / 255  # background noise and vignette, keyed out as well
OWL_ROWS = (0, 870)  # the owl ends at ~858, the wordmark starts at ~882
WORD_ROWS = (870, 1080)

DP = 2  # xhdpi


def key_out(rgb):
    """Smallest alpha with rgb = alpha·F + (1 − alpha)·BG, F in [0, 1]; returns straight (not premultiplied) F and alpha."""
    diff = rgb - BG
    up = np.clip(diff - DEAD_ZONE, 0, None) / (1 - BG - DEAD_ZONE)
    down = np.clip(-diff - DEAD_ZONE, 0, None) / (BG - DEAD_ZONE)
    alpha = np.clip(np.maximum(up, down).max(2), 0, 1)
    return alpha


def colour(rgb, alpha):
    a = alpha[..., None]
    return np.clip(np.where(a > 1e-4, (rgb - (1 - a) * BG) / np.maximum(a, 1e-4), 0), 0, 1)


def solid_body(alpha):
    """The owl's silhouette (holes filled, edge pulled in a little and softened) — fully opaque."""
    mask = ndimage.binary_fill_holes(alpha > 0.6)
    mask = ndimage.binary_opening(mask, iterations=2)
    mask = ndimage.binary_erosion(mask, iterations=3)
    return ndimage.gaussian_filter(mask.astype(float), 1.2)


def bbox(alpha, threshold=0.03):
    ys, xs = np.where(alpha > threshold)
    return xs.min(), ys.min(), xs.max() + 1, ys.max() + 1


def to_image(rgb, alpha):
    return Image.fromarray((np.dstack([rgb, alpha]) * 255 + 0.5).astype(np.uint8), "RGBA")


def fit(img, height):
    """Scales [img] to [height] px: premultiplied while resampling, so the transparent edge does not darken."""
    w = round(img.width * height / img.height)
    return img.convert("RGBa").resize((w, height), Image.LANCZOS).convert("RGBA")


def on_navy(size, *placed):
    canvas = Image.new("RGBA", size, tuple(int(c * 255 + 0.5) for c in BG) + (255,))
    for img, xy in placed:
        canvas.alpha_composite(img, xy)
    return canvas.convert("RGB")


def build(src):
    rgb = np.asarray(Image.open(src).convert("RGB")).astype(float) / 255
    alpha = key_out(rgb)

    top, bottom = OWL_ROWS
    owl_alpha = alpha[top:bottom].copy()
    owl_alpha = np.maximum(owl_alpha, solid_body(owl_alpha))
    owl = to_image(colour(rgb[top:bottom], owl_alpha), owl_alpha)
    # Square around the owl with its near glow; the far, faint glow is cut.
    x0, y0, x1, y1 = bbox(owl_alpha, 0.08)
    side = max(x1 - x0, y1 - y0) + 16
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    owl = owl.crop((cx - side // 2, cy - side // 2, cx - side // 2 + side, cy - side // 2 + side))
    # Farthest point of the body from the centre: the icon keeps it inside the adaptive icon's 66 dp safe circle.
    body = np.asarray(owl)[..., 3] > 200
    ys, xs = np.nonzero(body)
    body_radius = np.sqrt((xs - side / 2) ** 2 + (ys - side / 2) ** 2).max()

    top, bottom = WORD_ROWS
    word_alpha = alpha[top:bottom]
    word = to_image(colour(rgb[top:bottom], word_alpha), word_alpha)
    x0, y0, x1, y1 = bbox(word_alpha)
    word = word.crop((x0 - 2, y0 - 2, x1 + 2, y1 + 2))

    out = {}

    # TV banner 320×180 dp: owl on the left, wordmark on the right.
    bw, bh = 320 * DP, 180 * DP
    owl_b = fit(owl, 130 * DP)
    word_b = fit(word, 32 * DP)
    gap = 6 * DP
    x = (bw - owl_b.width - gap - word_b.width) // 2
    out["drawable-xhdpi/banner.png"] = on_navy(
        (bw, bh),
        (owl_b, (x, (bh - owl_b.height) // 2)),
        (word_b, (x + owl_b.width + gap, (bh - word_b.height) // 2 + 2 * DP)),
    )

    # Adaptive icon foreground, 108 dp at xxxhdpi (4 px per dp); the body's radius is 31 dp.
    size = 108 * 4
    scale = 31 * 4 / body_radius
    owl_i = fit(owl, round(owl.height * scale))
    fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    fg.alpha_composite(owl_i, ((size - owl_i.width) // 2, (size - owl_i.height) // 2))
    out["drawable-xxxhdpi/ic_launcher_owl.png"] = fg

    # In-app marks, drawn at exactly these sizes (Brand.kt).
    out["drawable-xhdpi/logo_owl.png"] = fit(owl, 36 * DP)
    out["drawable-xhdpi/logo_wordmark.png"] = fit(word, 18 * DP)
    return out


def main():
    root = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--src", default=os.path.join(root, "design", "logo", "sleepyowl.png"))
    p.add_argument("--res", default=os.path.join(root, "app", "src", "main", "res"))
    p.add_argument("--preview", help="also write the pictures over the app's background into this directory")
    args = p.parse_args()

    for path, img in build(args.src).items():
        dst = os.path.join(args.res, path)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        img.save(dst, optimize=True)
        print(f"{dst}  {img.width}×{img.height}")
        if args.preview:
            os.makedirs(args.preview, exist_ok=True)
            bg = Image.new("RGBA", img.size, (0x0A, 0x0B, 0x10, 255))
            bg.alpha_composite(img.convert("RGBA"))
            bg.convert("RGB").save(os.path.join(args.preview, os.path.basename(path)))


if __name__ == "__main__":
    main()

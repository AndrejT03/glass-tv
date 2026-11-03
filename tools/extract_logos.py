"""Lifts each app's logo out of its banner as a crisp transparent PNG (for the Top Shelf designs).

The separation is in logokit.py: edge pixels are unmixed rather than rounded to the nearest colour
(which used to leave white fringes and dashed lines), and edges are redrawn from a distance field,
so a 320 px banner gives a logo that stays sharp at Top Shelf size. Logos meant for the dark
designs get their dark wordmarks turned white (logokit.CONFIG "dark").

Usage: python extract_logos.py <banner dir> <out dir>
The banner dir holds <package>.png files (design/banners, from pull_banners.py); an apps.json next
to them adds labels. Packages without a banner keep their logo, and so do the ones make_tiles.py
draws from vector logos or with typeset labels (theirs are sharper).
"""
import glob
import json
import os
import sys

import numpy as np

import logokit as lk
from make_tiles import LABELS, VECTOR


def main():
    src, dst = sys.argv[1], sys.argv[2]
    os.makedirs(dst, exist_ok=True)
    apps_json = os.path.join(src, 'apps.json')
    labels = {k: v['label'] for k, v in json.load(open(apps_json)).items()} if os.path.exists(apps_json) else {}
    meta_path = os.path.join(dst, 'logos.json')
    meta = json.load(open(meta_path)) if os.path.exists(meta_path) else {}
    for path in sorted(glob.glob(os.path.join(src, '*.png'))):
        pkg = os.path.basename(path)[:-4]
        if pkg in VECTOR or pkg in LABELS:
            continue
        b = lk.Banner(path, lk.CONFIG.get(pkg))
        out = b.logo(max(2.0, 1600 / b.w), dark=True)
        ys, xs = np.where(out[..., 3] > 0.01)
        if len(xs) == 0:
            continue
        pad = 12
        out = out[max(0, ys.min() - pad):ys.max() + pad + 1, max(0, xs.min() - pad):xs.max() + pad + 1]
        im = lk.to_image(out)
        im.save(os.path.join(dst, pkg + '.png'), optimize=True)
        meta[pkg] = {'label': labels.get(pkg, meta.get(pkg, {}).get('label', pkg)), 'size': im.size,
                     'bg': '#%02x%02x%02x' % tuple(int(c * 255) for c in b.border)}
        print(f'{pkg:42s} {im.size}')
    json.dump(meta, open(meta_path, 'w'), indent=1)


if __name__ == '__main__':
    main()

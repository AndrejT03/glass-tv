"""Draws the TV apps' home-screen tiles and Top Shelf logos at full resolution.

Android TV banners are 320x180 rasters (a few are 480-640 px), but a focused tile is ~310 px wide at
1080p and ~620 px at 4K. Every tile keeps its banner's look, but nothing in it is upscaled raster:
- the layout (where the logo and the label sit) and the background come from the banner; the
  backgrounds are smooth gradients, which scale up without loss;
- the logo is the brand's own vector logo (design/vectors, rendered with headless Chromium) where
  there is one, otherwise it is rebuilt from the banner with logokit's distance fields;
- labels (TCL's utilities, Alexa, APKMirror) are typeset again in Roboto, the font of those
  banners, at the size and place they have on the banner.

Writes app/src/main/assets/tiles/<package>.webp (800x480, lossless) and, for the apps drawn from
vectors or with typeset labels, design/logos/<package>.png: the logo alone for the Top Shelf
designs (export_topshelf.py), five times the banner's size and coloured for dark boards.
Usage: python make_tiles.py <chrome-headless-shell>
"""
import glob
import os
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image, ImageDraw, ImageFont

import logokit as lk

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BANNERS = os.path.join(ROOT, 'design', 'banners')
VECTORS = os.path.join(ROOT, 'design', 'vectors')
LOGOS = os.path.join(ROOT, 'design', 'logos')
FONT = os.path.join(ROOT, 'design', 'fonts', 'Roboto.ttf')
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'tiles')
W, H = 800, 480
LOGO_SCALE = 5

# The 2025 Apple TV mark: white at the top, melting into colour toward the base.
IRIDESCENT = ('linear-gradient(180deg, rgba(255,255,255,.95) 15%, rgba(255,255,255,0) 85%), '
              'linear-gradient(105deg, #b9a6ff, #8fc8ff 30%, #a8f0c0 50%, #ffe99a 66%, #ffa8c0 86%)')
METAL = 'linear-gradient(180deg, #ffffff 0%, #ece8f4 45%, #c7c1d9 100%)'

# Vector logos. "fill" paints the whole logo (a colour or a CSS gradient); "replace" edits the SVG's
# own colours; "dark" overrides either for the logo on the dark Top Shelf boards.
VECTOR = {
    'com.google.android.youtube.tv': dict(svg='youtube', dark=dict(replace=[('rgb(40, 40, 40)', '#FFFFFF')])),
    'com.netflix.ninja': dict(svg='netflix'),
    'com.apple.atve.androidtv.appletv': dict(svg='appletv', fill=IRIDESCENT),
    'com.disney.disneyplus': dict(svg='disney', fill='#FFFFFF'),
    'com.amazon.amazonvideo.livingroom': dict(svg='prime', fill='#FFFFFF'),
    'com.wbd.stream': dict(svg='hbo2025', fill=METAL),
    'com.spotify.tv.android': dict(svg='spotify'),
    'org.xbmc.kodi': dict(svg='kodi', dark=dict(replace=[('#0a0a0a', '#FFFFFF')])),
    'tunein.player': dict(svg='tunein2017'),
    'com.stremio.one': dict(svg='stremio'),
    'com.tcl.smartalexa': dict(svg='alexa2024', fill='#FFFFFF'),
    'com.tcl.waterfall.overseas': dict(svg='tcl', fill='#FFFFFF', replace=[('fill:#ed1c24', 'fill:none')]),
    'com.google.android.youtube.tvmusic': dict(svg='ytmusic_icon'),
}

# Labels typeset again: (text, Roboto weight, knocked out of a white plate).
LABELS = {
    'com.tcl.browser': ('BrowseHere', 500, False),
    'com.tcl.dashboard': ('TCL HOME', 500, False),
    'com.tcl.exhibit': ('T-Exhibition', 500, False),
    'com.tcl.guard': ('Safety Guard', 500, False),
    'com.tcl.magiconnectfree': ('MagiConnect', 500, False),
    'com.tcl.ocean.instructions': ('User Guide', 500, False),
    'com.tcl.suspension': ('Quick panel', 500, False),
    'com.tcl.t_solo': ('T-solo', 500, False),
    'com.tcl.ui_mediaCenter': ('Media Player', 500, False),
    'com.tcl.smartalexa': ('works with alexa', 500, False),
    'com.apkmirror.helper.prod': ('APKMirror Installer', 500, False),
    'com.tcl.waterfall.overseas': ('CHANNEL', 900, True),
}

# Apps with no banner on the TV: (vector logo, background top, background bottom).
NO_BANNER = {'com.google.android.youtube.tvmusic': ('#262626', '#050505')}


class Vectors:
    """Renders SVG logos with headless Chromium, large and on a transparent background."""

    def __init__(self, chrome):
        self.chrome = chrome
        self.cache = {}

    def get(self, spec, dark=False):
        spec = {**spec, **(spec.get('dark', {}) if dark else {})}
        key = (spec['svg'], spec.get('fill'), tuple(spec.get('replace', ())))
        if key not in self.cache:
            self.cache[key] = self._render(spec)
        return self.cache[key]

    def _render(self, spec, w=2400, h=1400):
        svg = open(os.path.join(VECTORS, spec['svg'] + '.svg')).read()
        for a, b in spec.get('replace', ()):
            svg = svg.replace(a, b)
        with tempfile.TemporaryDirectory() as tmp:
            open(os.path.join(tmp, 'logo.svg'), 'w').write(svg)
            if 'fill' in spec:
                body = (f'<div style="width:{w}px;height:{h}px;background:{spec["fill"]};'
                        f'-webkit-mask:url(logo.svg) center/contain no-repeat"></div>')
            else:
                body = f'<img src="logo.svg" style="width:{w}px;height:{h}px;object-fit:contain;display:block">'
            open(os.path.join(tmp, 'page.html'), 'w').write(
                f'<!doctype html><html><head><style>html,body{{margin:0;background:transparent}}</style></head><body>{body}</body></html>')
            shot = os.path.join(tmp, 'shot.png')
            subprocess.run([self.chrome, '--headless', '--hide-scrollbars', '--allow-file-access-from-files',
                            '--default-background-color=00000000', f'--window-size={w},{h}',
                            f'--screenshot={shot}', 'file://' + os.path.join(tmp, 'page.html')], check=True, capture_output=True)
            rgba = np.asarray(Image.open(shot).convert('RGBA')).astype(np.float32) / 255
        return trim(rgba)


def trim(rgba):
    ys, xs = np.where(rgba[..., 3] > 0.004)
    return rgba[ys.min():ys.max() + 1, xs.min():xs.max() + 1]


def resize_rgba(rgba, w, h):
    """Resamples straight RGBA through premultiplied colour, so edges don't pick up dark fringes."""
    a = rgba[..., 3:]
    pm = np.concatenate([rgba[..., :3] * a, a], -1)
    pm = np.clip(lk.resize(pm, max(1, w), max(1, h), Image.LANCZOS), 0, 1)
    return np.concatenate([pm[..., :3] / np.maximum(pm[..., 3:], 1e-6), pm[..., 3:]], -1)


def put(dst, src, box):
    """Draws src over dst (both straight RGBA), fitted inside box = (x0, y0, x1, y1) and centred."""
    x0, y0, x1, y1 = box
    s = min((x1 - x0) / src.shape[1], (y1 - y0) / src.shape[0])
    w, h = round(src.shape[1] * s), round(src.shape[0] * s)
    src = resize_rgba(src, w, h)
    x = round((x0 + x1 - w) / 2)
    y = round((y0 + y1 - h) / 2)
    over_rgba(dst, src, x, y)


def over_rgba(dst, src, x, y):
    H_, W_ = dst.shape[:2]
    sx0, sy0 = max(0, -x), max(0, -y)
    x0, y0 = max(0, x), max(0, y)
    x1, y1 = min(W_, x + src.shape[1]), min(H_, y + src.shape[0])
    if x1 <= x0 or y1 <= y0:
        return
    s = src[sy0:sy0 + y1 - y0, sx0:sx0 + x1 - x0]
    d = dst[y0:y1, x0:x1]
    sa, da = s[..., 3:], d[..., 3:]
    a = sa + da * (1 - sa)
    c = (s[..., :3] * sa + d[..., :3] * da * (1 - sa)) / np.maximum(a, 1e-6)
    dst[y0:y1, x0:x1] = np.concatenate([c, a], -1)


def text_mask(text, weight, height):
    """The label's ink, `height` px tall, anti-aliased from a large rendering."""
    font = ImageFont.truetype(FONT, 400)
    font.set_variation_by_axes([weight, 100, 0])
    l, t, r, b = font.getbbox(text)
    img = Image.new('L', (r - l + 80, b - t + 80))
    ImageDraw.Draw(img).text((40 - l, 40 - t), text, font=font, fill=255)
    img = img.crop(img.getbbox())
    w = round(img.width * height / img.height)
    return np.asarray(img.resize((max(1, w), max(1, round(height))), Image.LANCZOS)).astype(np.float32) / 255


def runs(rows):
    """Splits sorted row indices into runs separated by at least two empty rows."""
    out = [[rows[0], rows[0]]]
    for r in rows[1:]:
        if r - out[-1][1] > 2:
            out.append([r, r])
        else:
            out[-1][1] = r
    return out


def layout(b, label):
    """Boxes (banner px, x0 y0 x1 y1) of the logo and of its label (the lowest line), and the label colour."""
    a = np.clip(1 - b.cov[0], 0, 1)
    rows = np.nonzero(a.max(1) > 0.5)[0]
    bands = runs(rows)
    if label and len(bands) > 1:
        text_rows, icon_rows = bands[-1], [bands[0][0], bands[-2][1]]
    else:
        text_rows, icon_rows = None, [bands[0][0], bands[-1][1]]

    def box(r0, r1):
        cols = np.nonzero(a[r0:r1 + 1].max(0) > 0.5)[0]
        return [cols.min(), r0, cols.max() + 1, r1 + 1]

    icon = box(*icon_rows)
    if text_rows is None:
        return icon, None, None
    text = box(*text_rows)
    band = (slice(text_rows[0], text_rows[1] + 1), slice(text[0], text[2]))
    solid = (a[band] > 0.95) & b.solid[band]
    color = np.median(b.rgb[band][solid], 0) if solid.any() else np.array([1, 1, 1], np.float32)
    # The label leaves the logo (it is typeset again) but stays out of the background model.
    r0, r1 = max(0, text_rows[0] - 1), text_rows[1] + 2
    b.cov[0][r0:r1, text[0]:text[2]] = 1
    b.cov[1:, r0:r1, text[0]:text[2]] = 0
    return icon, text, color


def logo_layer(pkg, b, boxes, scale, vectors, dark):
    """The logo and label over a transparent banner-sized frame, `scale` px per banner px."""
    icon, text, color = boxes
    fw, fh = round(b.w * scale), round(b.h * scale)
    layer = np.zeros((fh, fw, 4), np.float32)
    if pkg in VECTOR:
        put(layer, vectors.get(VECTOR[pkg], dark), [v * scale for v in icon])
    else:
        art = b.logo(scale, dark=dark)
        over_rgba(layer, art[:fh, :fw], 0, 0)
    if text is not None:
        word, weight, plate = LABELS[pkg]
        x0, y0, x1, y1 = [v * scale for v in text]
        if plate:
            # White plate with the word cut out of it, so the background shows through the letters.
            pw, ph = round(x1 - x0), round(y1 - y0)
            m = text_mask(word, weight, ph * 0.62)
            if m.shape[1] > pw * 0.88:
                m = text_mask(word, weight, ph * 0.62 * pw * 0.88 / m.shape[1])
            p = np.ones((ph, pw, 4), np.float32)
            mx, my = (pw - m.shape[1]) // 2, (ph - m.shape[0]) // 2
            p[my:my + m.shape[0], mx:mx + m.shape[1], 3] -= m[:ph - my, :pw - mx]
            over_rgba(layer, p, round(x0), round(y0))
        else:
            m = text_mask(word, weight, y1 - y0)
            ratio = m.shape[1] / (x1 - x0)
            if abs(ratio - 1) > 0.08:
                print(f'  note: {word!r} typesets {ratio:.2f}x the banner label width')
            t = np.concatenate([np.broadcast_to(color, m.shape + (3,)), m[..., None]], -1).astype(np.float32)
            over_rgba(layer, t, round((x0 + x1 - m.shape[1]) / 2), round(y0))
    return layer


def tile(pkg, path, vectors):
    b = lk.Banner(path, lk.CONFIG.get(pkg))
    scale = max(W / b.w, H / b.h)
    if scale <= 1:
        # Already big enough (a 1280x720 banner): only resized.
        img = np.asarray(Image.open(path).convert('RGB').resize((round(b.w * scale), round(b.h * scale)), Image.LANCZOS))
        return crop(img.astype(np.float32) / 255), None
    bg_src = b.background()
    if b.w / b.h < 1.3:
        # An icon rather than a banner: its background stretched, the logo centred on the tile.
        side = max(W, H)
        bg = crop(lk.resize(b.bg, side, side))
        art = vectors.get(VECTOR[pkg]) if pkg in VECTOR else trim(b.logo(max(2.0, 1600 / b.w)))
        layer = np.zeros((H, W, 4), np.float32)
        put(layer, art, [W * 0.25, H * 0.27, W * 0.75, H * 0.73])
        out = lk.over(bg.copy(), layer)
        return out, vectors.get(VECTOR[pkg], dark=True) if pkg in VECTOR else None
    boxes = layout(b, pkg in LABELS)
    fw, fh = round(b.w * scale), round(b.h * scale)
    bg = np.clip(lk.resize(bg_src, fw, fh), 0, 1)
    out = crop(lk.over(bg, logo_layer(pkg, b, boxes, scale, vectors, dark=False)))
    logo = None
    if pkg in VECTOR or pkg in LABELS:
        logo = trim(logo_layer(pkg, b, boxes, LOGO_SCALE, vectors, dark=True))
    return out, logo


def no_banner(pkg, colors, vectors):
    top, bottom = lk.hex_rgb(colors[0]), lk.hex_rgb(colors[1])
    t = np.linspace(0, 1, H, dtype=np.float32)[:, None, None]
    bg = np.broadcast_to(top * (1 - t) + bottom * t, (H, W, 3)).copy()
    layer = np.zeros((H, W, 4), np.float32)
    put(layer, vectors.get(VECTOR[pkg]), [W * 0.3, H * 0.22, W * 0.7, H * 0.78])
    return lk.over(bg, layer), vectors.get(VECTOR[pkg], dark=True)


def crop(a):
    y = (a.shape[0] - H) // 2
    x = (a.shape[1] - W) // 2
    return a[y:y + H, x:x + W]


def main():
    vectors = Vectors(sys.argv[1])
    os.makedirs(OUT, exist_ok=True)
    jobs = [(os.path.basename(p)[:-4], p) for p in sorted(glob.glob(os.path.join(BANNERS, '*.png')))]
    for pkg, path in jobs:
        img, logo = tile(pkg, path, vectors)
        save(pkg, img, logo)
    for pkg, colors in NO_BANNER.items():
        save(pkg, *no_banner(pkg, colors, vectors))


def save(pkg, img, logo):
    out = os.path.join(OUT, pkg + '.webp')
    lk.to_image(img).save(out, lossless=True, quality=100, method=6)
    note = ''
    if logo is not None:
        lk.to_image(logo).save(os.path.join(LOGOS, pkg + '.png'), optimize=True)
        note = f'  logo {logo.shape[1]}x{logo.shape[0]}'
    print(f'{pkg:42s} {os.path.getsize(out) // 1024:4d} KB{note}')


if __name__ == '__main__':
    main()

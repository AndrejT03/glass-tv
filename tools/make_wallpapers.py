"""Generates the bundled 'glass wave' wallpapers (1920x1080) and the launcher banner.

Usage: python make_wallpapers.py <app/src/main> <path to InterDisplay-Bold.ttf>
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

W, H = 1920, 1080
rng = np.random.default_rng(7)


def hex3(h):
    h = h.lstrip('#')
    return np.array([int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4)], dtype=np.float32)


def to_lin(c):
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def to_srgb(c):
    c = np.clip(c, 0, 1)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1 / 2.4) - 0.055)


X, Y = np.meshgrid(np.arange(W, dtype=np.float32), np.arange(H, dtype=np.float32))
U, V = X / W, Y / H


def glow(cx, cy, r, color, strength):
    d2 = ((X - cx * W) ** 2 + (Y - cy * H) ** 2) / (r * W) ** 2
    return np.exp(-d2)[..., None] * to_lin(hex3(color)) * strength


def wave_layer(img, base, amps, color_a, color_b, alpha, edge, phase):
    """Composites one translucent glass band whose top edge is a sum of sines."""
    xs = np.arange(W, dtype=np.float32) / W
    y = np.full(W, base * H, dtype=np.float32)
    dy = np.zeros(W, dtype=np.float32)
    for i, (a, f) in enumerate(amps):
        y += a * H * np.sin(2 * np.pi * f * xs + phase * (i + 1.3))
        dy += a * H * 2 * np.pi * f / W * np.cos(2 * np.pi * f * xs + phase * (i + 1.3))
    norm = np.sqrt(1 + dy ** 2)
    d = (Y - y[None, :]) / norm[None, :]          # signed distance below the edge, in px
    inside = np.clip(d / 1.5 + 0.5, 0, 1)          # anti-aliased coverage

    t = np.clip(U + 0.15 * np.sin(V * 3 + phase), 0, 1)[..., None]
    body = to_lin(hex3(color_a)) * (1 - t) + to_lin(hex3(color_b)) * t
    depth = np.clip(d / (0.55 * H), 0, 1)[..., None]
    shade = 1.0 + 0.4 * np.exp(-np.maximum(d, 0) / 55)[..., None] - 0.62 * depth
    body = body * shade

    a = (inside * alpha)[..., None]
    img[:] = img * (1 - a) + body * a

    # specular rim: catches light at the crests and fades along the curve (liquid glass highlight)
    m = (0.5 + 0.5 * np.sin(2 * np.pi * xs * 1.15 + phase * 2.2)) ** 2
    m = (0.22 + 0.78 * m / (1 + (dy * 2.5) ** 2))[None, :]
    rim = (np.exp(-(d / 1.25) ** 2) * 0.95 + np.exp(-(d / 9) ** 2) * 0.12) * edge * m
    inner = np.exp(-((d - 16) / 5) ** 2) * 0.09 * edge * m      # second, refracted edge inside the band
    rim_col = to_lin(hex3('#ffffff')) * 0.7 + body.mean(axis=(0, 1)) * 0.3
    img += (rim + inner)[..., None] * rim_col
    above = np.exp(-np.maximum(-d, 0) / 38) * (d < 0) * 0.07 * edge * m
    img += above[..., None] * to_lin(hex3(color_b))


PALETTES = {
    'tahoe': dict(bg=('#010515', '#0a1b55'), glows=[(0.18, 0.12, 0.45, '#1f4fd1', 0.35), (0.85, 0.3, 0.35, '#5a3cff', 0.25)],
                  layers=[('#0e2f8f', '#3a6cff', 0.62), ('#1f5fe0', '#6fd0ff', 0.55), ('#3b2fd0', '#8f7bff', 0.5), ('#0b47c8', '#47b6ff', 0.6)]),
    'sunrise': dict(bg=('#12041a', '#4a0f30'), glows=[(0.8, 0.35, 0.4, '#ff6a3d', 0.28), (0.2, 0.25, 0.4, '#b0236e', 0.3)],
                    layers=[('#8c1d56', '#ff5f6d', 0.6), ('#d6406a', '#ffa45c', 0.55), ('#ff7a45', '#ffd27a', 0.5), ('#b8205e', '#ff8a5c', 0.6)]),
    'aurora': dict(bg=('#010a10', '#032a33'), glows=[(0.25, 0.1, 0.45, '#0fa3a3', 0.3), (0.8, 0.22, 0.35, '#6b4dff', 0.25)],
                   layers=[('#05606a', '#18c7b4', 0.6), ('#0d8f7a', '#5ef0a0', 0.5), ('#3a2fb0', '#8a6cff', 0.5), ('#08707a', '#2fe0c8', 0.6)]),
    'dusk': dict(bg=('#0b0726', '#2c1c62'), glows=[(0.7, 0.3, 0.45, '#b06bff', 0.26), (0.15, 0.3, 0.4, '#5b7bff', 0.28)],
                 layers=[('#4b33c9', '#a77bff', 0.6), ('#8a4fe0', '#ff8ad8', 0.52), ('#5f7bff', '#b8d0ff', 0.45), ('#6a3fd8', '#ff9ccf', 0.58)]),
    'graphite': dict(bg=('#060608', '#17181d'), glows=[(0.3, 0.15, 0.5, '#3b4252', 0.35), (0.85, 0.3, 0.4, '#2a3140', 0.3)],
                     layers=[('#262a33', '#5d6575', 0.6), ('#3a404c', '#8d96a8', 0.5), ('#2b303a', '#6c7486', 0.55), ('#31363f', '#a3acbd', 0.6)]),
    'midnight': dict(bg=('#000000', '#050a18'), glows=[(0.5, 0.95, 0.6, '#123a9c', 0.3)],
                     layers=[('#071233', '#1d4fd8', 0.55), ('#0a1a4a', '#3d7bff', 0.45)]),
}

WAVES = [
    (0.60, [(0.10, 0.42), (0.028, 1.3)], 0.9),
    (0.68, [(0.085, 0.58), (0.022, 1.75)], 1.0),
    (0.77, [(0.075, 0.5), (0.02, 1.45)], 0.9),
    (0.87, [(0.055, 0.8), (0.014, 2.2)], 0.8),
]


def make(name, p):
    top, bottom = (to_lin(hex3(c)) for c in p['bg'])
    t = (V * 0.85 + U * 0.15)[..., None]
    img = top * (1 - t) + bottom * t
    for g in p['glows']:
        img += glow(*g)
    phase = {'tahoe': 0.4, 'sunrise': 1.7, 'aurora': 2.9, 'dusk': 4.1, 'graphite': 5.3, 'midnight': 0.9}[name]
    for (base, amps, edge), (ca, cb, alpha) in zip(WAVES, p['layers']):
        wave_layer(img, base, amps, ca, cb, alpha, edge, phase)
    vig = 1 - 0.35 * (((U - 0.5) ** 2 + (V - 0.45) ** 2) * 1.6)
    img *= np.clip(vig, 0, 1)[..., None]
    out = to_srgb(img) * 255 + rng.uniform(-1.2, 1.2, img.shape)  # dither against banding on TV panels
    return Image.fromarray(np.clip(out, 0, 255).astype(np.uint8))


def main():
    main_dir, font_path = sys.argv[1], sys.argv[2]
    wp_dir = os.path.join(main_dir, 'assets', 'wallpapers')
    os.makedirs(wp_dir, exist_ok=True)
    for name, p in PALETTES.items():
        im = make(name, p)
        im.save(os.path.join(wp_dir, name + '.jpg'), quality=93, subsampling=0, optimize=True)
        print('wallpaper', name)

    # Launcher banner (320x180) and icon
    base = Image.open(os.path.join(wp_dir, 'dusk.jpg')).resize((320, 180), Image.LANCZOS)
    d = ImageDraw.Draw(base)
    f = ImageFont.truetype(font_path, 44)
    text = 'Glass'
    w = d.textlength(text, font=f)
    d.text(((320 - w) / 2, 58), text, font=f, fill=(255, 255, 255))
    base.save(os.path.join(main_dir, 'res', 'drawable', 'banner.png'))
    icon = Image.open(os.path.join(wp_dir, 'dusk.jpg')).crop((420, 0, 1500, 1080)).resize((192, 192), Image.LANCZOS)
    d = ImageDraw.Draw(icon)
    f = ImageFont.truetype(font_path, 120)
    w = d.textlength('G', font=f)
    d.text(((192 - w) / 2, 18), 'G', font=f, fill=(255, 255, 255))
    icon.save(os.path.join(main_dir, 'res', 'mipmap-xhdpi', 'ic_launcher.png'))


if __name__ == '__main__':
    main()

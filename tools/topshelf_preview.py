"""Prototype: tvOS-style Top Shelf from a low-res Android TV banner (logo kept crisp)."""
import sys, os, glob
import numpy as np
from PIL import Image, ImageFilter

W, H = 1920, 1080

def box_blur(a, r, passes=3):
    from scipy.ndimage import uniform_filter
    for _ in range(passes):
        a = uniform_filter(a, size=(2*r+1, 2*r+1, 1), mode='nearest')
    return a

def bicubic(a, out_w, out_h):
    im = Image.fromarray(a.astype(np.float32)) if a.ndim == 2 else None
    return np.asarray(Image.fromarray(a.astype(np.float32)).resize((out_w, out_h), Image.BICUBIC))

def resize_rgb(a, w, h, method=Image.BILINEAR):
    ch = [np.asarray(Image.fromarray(a[..., i].astype(np.float32)).resize((w, h), method)) for i in range(3)]
    return np.stack(ch, -1)

def make(path, out):
    b = Image.open(path).convert('RGBA')
    bw, bh = b.size
    rgba = np.asarray(b).astype(np.float32) / 255
    border = np.concatenate([rgba[0], rgba[-1], rgba[:, 0], rgba[:, -1]])
    bgc = border[:, :3].mean(0)
    rgb = rgba[..., :3] * rgba[..., 3:] + bgc * (1 - rgba[..., 3:])     # flatten transparency onto border colour
    # Background model that ignores the logo: blur only pixels believed to be background
    # (normalised convolution), refined twice.
    r = max(4, bw // 12)
    bg_local = box_blur(rgb, r)
    for _ in range(3):
        dist = np.sqrt(((rgb - bg_local) ** 2).sum(-1) / 3)
        a = np.clip((dist - 0.03) / 0.10, 0, 1)
        w = (1 - a)[..., None] ** 2 + 1e-4
        bg_local = box_blur(rgb * w, r) / box_blur(w, r)
    dist = np.sqrt(((rgb - bg_local) ** 2).sum(-1) / 3)
    a = np.clip((dist - 0.03) / 0.10, 0, 1)
    wbg = (1 - a)[..., None] ** 2 + 1e-4
    # logo colour, with the background blended out of anti-aliased edge pixels
    logo = np.clip(bg_local + (rgb - bg_local) / np.maximum(a, 0.08)[..., None], 0, 1)
    mid = ((a > 0.15) & (a < 0.85)).mean()
    square = bw / bh < 1.3
    s = (H * 0.36) / bh if square else (W * 0.62) / bw
    tw, th = int(bw * s), int(bh * s)
    x0, y0 = (W - tw) // 2, int(H * 0.40 - th / 2)
    # one smooth, logo-free background model, used everywhere: no seams around the banner
    R = max(6, bw // 6)
    bg_small = box_blur(rgb * wbg, R) / box_blur(wbg, R)
    bgarr = resize_rgb(bg_small, tw, th)
    yy = np.clip(np.arange(H) - y0, 0, th - 1); xx = np.clip(np.arange(W) - x0, 0, tw - 1)
    full = bgarr[yy][:, xx].copy()
    a_up = bicubic(a, tw, th)
    k = 0.6 / s
    t = np.clip((a_up - (0.5 - k)) / (2 * k), 0, 1); a_sharp = t * t * (3 - 2 * t)
    logo_up = resize_rgb(logo, tw, th)
    region = full[y0:y0 + th, x0:x0 + tw]
    full[y0:y0 + th, x0:x0 + tw] = region * (1 - a_sharp[..., None]) + logo_up * a_sharp[..., None]
    U, V = np.meshgrid(np.linspace(-1, 1, W), np.linspace(-1, 1, H))
    full *= (1 - 0.12 * (U ** 2 + V ** 2))[..., None]
    Image.fromarray((np.clip(full, 0, 1) * 255).astype(np.uint8)).save(out, quality=92)
    return mid

paths = sorted(glob.glob(os.path.join(sys.argv[1], '*.png')))
for p in paths:
    out = os.path.join(sys.argv[2], os.path.basename(p)[:-4] + '.jpg')
    print(os.path.basename(p), 'mid-alpha fraction %.3f' % make(p, out))

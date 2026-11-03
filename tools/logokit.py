"""Lifts an app's logo out of its banner and redraws it at any size with clean, sharp edges.

Android TV banners are small rasters (usually 320x180). A pixel on the edge of a logo is a mix of
the background and one or two logo colours. Earlier versions rounded such pixels to the nearest
colour, which left light fringes and dashed lines around logos. This works out the mix instead
(linear unmixing against a logo-free model of the background), so every edge keeps its sub-pixel
position; the coverage is turned into a signed distance field, upscaled, and drawn with a
one-pixel edge at the target size, the way distance-field text is rendered. Edges stay crisp at
any scale, without fringes or staircases.

Used by extract_logos.py (transparent logos for the Top Shelf designs) and make_tiles.py (tile art).
Needs numpy, scipy and Pillow.
"""
import numpy as np
from PIL import Image
from scipy.ndimage import (binary_dilation, binary_fill_holes, distance_transform_edt, gaussian_filter,
                           label, maximum_filter, uniform_filter)

# How each logo is separated. "flat": solid colours (k of them, or an explicit palette), unmixed
# pairwise, so edges between two logo colours are as sharp as edges against the background.
# "grad": gradients or shading; one logo colour per pixel, taken from the nearest solid pixel.
# "holes": (colour, palette index) fills the background enclosed by that colour (the YouTube
# triangle is a hole in the red button), but not the counters of letters in other colours.
# "dark": the wordmark colours to use on dark designs, by palette index.
CONFIG = {
    'com.google.android.youtube.tv': {'mode': 'flat', 'palette': ['#FF0000', '#282828'], 'holes': ('#FFFFFF', 0),
                                      'button': True, 'dark': {1: '#FFFFFF'}},
    'com.netflix.ninja': {'mode': 'flat', 'palette': ['#E50914']},
    'com.apple.atve.androidtv.appletv': {'mode': 'grad'},
    'com.disney.disneyplus': {'mode': 'flat', 'k': 1},
    'com.amazon.amazonvideo.livingroom': {'mode': 'flat', 'k': 1},
    'com.wbd.stream': {'mode': 'grad'},
    'com.spotify.tv.android': {'mode': 'flat', 'k': 1},
    'com.stremio.one': {'mode': 'grad'},
    'org.xbmc.kodi': {'mode': 'flat', 'k': 2, 'dark': {'gray': '#FFFFFF'}},
    'tunein.player': {'mode': 'flat', 'k': 1},
    'com.google.android.videos': {'mode': 'grad'},
    'com.google.android.play.games': {'mode': 'grad'},
    'com.android.tv.settings': {'mode': 'flat', 'k': 1},
    'com.esaba.downloader': {'mode': 'flat', 'k': 1},
    'com.apkmirror.helper.prod': {'mode': 'flat', 'k': 1},
    'com.tv.remote.button.mapper.remap': {'mode': 'grad'},
    'com.tcl.browser': {'mode': 'grad'},
    'com.tcl.dashboard': {'mode': 'flat', 'k': 3},
    'com.tcl.exhibit': {'mode': 'grad'},
    'com.tcl.guard': {'mode': 'grad'},
    'com.tcl.magiconnectfree': {'mode': 'grad'},
    'com.tcl.ocean.instructions': {'mode': 'grad'},
    'com.tcl.smartalexa': {'mode': 'flat', 'k': 1},
    'com.tcl.suspension': {'mode': 'grad'},
    'com.tcl.t_solo': {'mode': 'grad'},
    'com.tcl.tv': {'mode': 'flat', 'k': 1},
    'com.tcl.ui_mediaCenter': {'mode': 'grad'},
    'com.tcl.waterfall.overseas': {'mode': 'flat', 'k': 1},
}


def hex_rgb(h):
    h = h.lstrip('#')
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], np.float32) / 255


def blur(a, r, passes=3):
    for _ in range(passes):
        a = uniform_filter(a, size=(2 * r + 1, 2 * r + 1) + ((1,) if a.ndim == 3 else ()), mode='nearest')
    return a


def wblur(rgb, w, r):
    return blur(rgb * w[..., None], r) / blur(w, r)[..., None]


def resize(a, w, h, method=Image.BICUBIC):
    """Resizes a float array, (H, W) or (H, W, C), channel by channel."""
    if a.ndim == 2:
        return np.asarray(Image.fromarray(a.astype(np.float32), 'F').resize((w, h), method))
    return np.stack([resize(a[..., i], w, h, method) for i in range(a.shape[2])], -1)


def distance(cov):
    """Signed distance to the edge (pixels, positive outside) from anti-aliased coverage.

    Edge pixels use the coverage and the edge direction (Gustavson & Strand's anti-aliased distance
    transform), so a straight edge gets a linear field that interpolates without wobble; pixels away
    from edges use the plain Euclidean distance transform.
    """
    c = np.clip(cov, 0, 1)
    p = np.pad(c, 1, mode='edge')
    r2 = np.sqrt(2)
    gx = -p[:-2, :-2] - r2 * p[1:-1, :-2] - p[2:, :-2] + p[:-2, 2:] + r2 * p[1:-1, 2:] + p[2:, 2:]
    gy = -p[:-2, :-2] - r2 * p[:-2, 1:-1] - p[:-2, 2:] + p[2:, :-2] + r2 * p[2:, 1:-1] + p[2:, 2:]
    g = np.hypot(gx, gy)
    hi = np.maximum(np.abs(gx), np.abs(gy)) / np.maximum(g, 1e-9)
    lo = np.minimum(np.abs(gx), np.abs(gy)) / np.maximum(g, 1e-9)
    a1 = 0.5 * lo / np.maximum(hi, 1e-9)
    near = np.where(c < a1, 0.5 * (hi + lo) - np.sqrt(np.maximum(2 * hi * lo * c, 0)),
                    np.where(c < 1 - a1, (0.5 - c) * hi, -0.5 * (hi + lo) + np.sqrt(np.maximum(2 * hi * lo * (1 - c), 0))))
    near = np.where(g < 1e-6, 0.5 - c, near)
    inside = c >= 0.5
    far = np.where(inside, 0.5 - distance_transform_edt(inside), distance_transform_edt(~inside) - 0.5)
    return np.where((c > 0.004) & (c < 0.996), near, far)


def edge(cov, w, h, scale):
    """Coverage redrawn at w x h (`scale` output pixels per source pixel) with one-pixel-wide edges.

    A slight blur of the distance field irons out the source's uneven anti-aliasing; it leaves
    straight edges where they are and only softens corners by a fraction of a source pixel.
    """
    d = resize(gaussian_filter(distance(cov), 0.5), w, h, Image.BILINEAR)
    return np.clip(0.5 - d * scale, 0, 1)


def kmeans(pts, k, iters=30):
    rng = np.random.default_rng(1)
    cent = pts[rng.choice(len(pts), k, replace=False)]
    for _ in range(iters):
        lab = ((pts[:, None] - cent[None]) ** 2).sum(-1).argmin(1)
        cent = np.stack([pts[lab == i].mean(0) if (lab == i).any() else cent[i] for i in range(k)])
    return cent, lab


class Banner:
    """A banner split into a logo-free background and its logo."""

    def __init__(self, path, config=None):
        self.config = config or {'mode': 'grad'}
        im = Image.open(path).convert('RGBA')
        self.w, self.h = im.size
        rgba = np.asarray(im).astype(np.float32) / 255
        border = np.concatenate([rgba[0], rgba[-1], rgba[:, 0], rgba[:, -1]])
        opaque = border[border[:, 3] > 0.5]
        self.border = opaque[:, :3].mean(0) if len(opaque) else rgba[..., :3][rgba[..., 3] > 0.5].mean(0) * 0.3
        a = rgba[..., 3:]
        self.rgb = rgba[..., :3] * a + self.border * (1 - a)

        # Background model that ignores the logo: a blur of the pixels believed to be background.
        r = max(4, self.w // 12)
        bg = blur(self.rgb, r)
        for _ in range(3):
            bg = wblur(self.rgb, (1 - self._steep(bg)) ** 2 + 1e-4, r)
        self.bg = bg
        m = self._steep(bg)
        # Shapes connected to the edges are background (glows, bands, long shadows), not logo.
        lab, _ = label(m > 0.2)
        edge = np.unique(np.concatenate([lab[0], lab[-1], lab[:, 0], lab[:, -1]]))
        m[np.isin(lab, edge[edge > 0])] = 0
        m[~binary_dilation(m > 0.2, iterations=2)] = 0
        self.mask = m
        self.region = binary_dilation(m > 0.02, iterations=2)
        # Solid logo pixels: clearly logo, and as far from the background as their neighbours get.
        self.dist = np.sqrt(((self.rgb - bg) ** 2).sum(-1) / 3)
        self.solid = (m > 0.9) & (self.dist >= 0.85 * maximum_filter(self.dist, 3))
        self.cov, self.colors = self._flat() if self.config['mode'] == 'flat' else self._grad()

    def _steep(self, bg):
        dist = np.sqrt(((self.rgb - bg) ** 2).sum(-1) / 3)
        return np.clip((dist - 0.03) / 0.10, 0, 1)

    def _flat(self):
        """Coverage of the background (index 0) and of each palette colour, by pairwise unmixing."""
        cfg = self.config
        if 'palette' in cfg:
            pal = np.stack([hex_rgb(c) for c in cfg['palette']])
        else:
            pts = self.rgb[self.solid]
            pal, lab = kmeans(pts, cfg['k'])
            # Each colour from its purest pixels: the half farthest from the background.
            far = self.dist[self.solid]
            pal = np.stack([pts[lab == i][far[lab == i] >= np.median(far[lab == i])].mean(0) if (lab == i).any() else pal[i]
                            for i in range(len(pal))])
        n = len(pal)
        x = self.rgb
        ends = [self.bg] + [np.broadcast_to(c, x.shape) for c in pal]
        pairs = [(0, i) for i in range(1, n + 1)] + [(i, j) for i in range(1, n + 1) for j in range(i + 1, n + 1)]
        best_err = np.full(x.shape[:2], np.inf, np.float32)
        best_t = np.zeros(x.shape[:2], np.float32)
        best_p = np.zeros(x.shape[:2], np.int32)
        for p, (u, v) in enumerate(pairs):
            d = ends[v] - ends[u]
            t = np.clip(((x - ends[u]) * d).sum(-1) / np.maximum((d * d).sum(-1), 1e-6), 0, 1)
            err = ((x - ends[u] - t[..., None] * d) ** 2).sum(-1)
            if u > 0:
                err = err + 2e-4  # an edge against the background is the likelier reading
            better = err < best_err
            best_err[better], best_t[better], best_p[better] = err[better], t[better], p
        cov = np.zeros((n + 1,) + x.shape[:2], np.float32)
        for p, (u, v) in enumerate(pairs):
            sel = best_p == p
            cov[u][sel] += 1 - best_t[sel]
            cov[v][sel] += best_t[sel]
        cov[:, ~self.region] = 0
        cov[0][~self.region] = 1
        colors = [c for c in pal]
        if 'holes' in cfg:
            # Background enclosed by one logo colour becomes a colour of its own (the YouTube triangle).
            hole_color, around = cfg['holes']
            solid = cov[1 + around] >= 0.5
            holes = binary_fill_holes(solid) & ~solid & (cov[0] >= 0.5)
            holes = binary_dilation(holes, iterations=1) & (cov[0] > 0)
            hole = np.where(holes, cov[0], 0)
            cov[0] = np.where(holes, 0, cov[0])
            cov = np.concatenate([cov, hole[None]])
            colors.append(hex_rgb(hole_color))
        if cfg.get('button'):
            play_button(cov)
        return cov, np.stack(colors)

    def _grad(self):
        """Coverage of one logo layer whose colour varies: unmixed against the nearest solid colour."""
        solid = self.solid if self.solid.any() else self.mask > 0.9
        _, (iy, ix) = distance_transform_edt(~solid, return_indices=True)
        near = self.rgb[iy, ix]
        d = near - self.bg
        t = ((self.rgb - self.bg) * d).sum(-1) / np.maximum((d * d).sum(-1), 1e-4)
        alpha = np.clip(t, 0, 1)
        # Where logo and background are too alike to unmix, fall back to the plain mask.
        weak = (d * d).sum(-1) < 0.01
        alpha[weak] = self.mask[weak]
        alpha[~self.region] = 0
        alpha[solid] = 1
        # Inside the logo every pixel keeps its own colour (soft shading, two-tone edges); only the
        # rim, which is part background, takes the colour of the nearest solid pixel.
        self.field = np.where((solid | (alpha >= 0.98))[..., None], self.rgb, near)
        return np.stack([1 - alpha, alpha]), None

    def palette(self, dark=False):
        """The logo colours, optionally with the wordmark colours swapped for use on dark designs."""
        if self.colors is None:
            return None
        pal = self.colors.copy()
        for key, c in (self.config.get('dark', {}) if dark else {}).items():
            if key == 'gray':
                for i, p in enumerate(pal):
                    if p.max() - p.min() < 0.2 and p.mean() < 0.5:
                        pal[i] = hex_rgb(c)
            else:
                pal[key] = hex_rgb(c)
        return pal

    def background(self):
        """The banner without its logo: its own pixels, with the logo filled in from around it.

        The hole is filled from the nearest background pixels (normalised blurs of growing radius),
        so gradients and glows carry on under the logo without halos or seams.
        """
        hole = binary_dilation((1 - self.cov[0]) > 0.05, iterations=2)
        keep = (~hole).astype(np.float32)
        out = self.rgb.copy()
        done = ~hole
        for r in (1, 2, 4, 8, 16, 32, 64):
            den = blur(keep, r, passes=2)
            ok = (den > 0.02) & ~done
            out[ok] = (blur(self.rgb * keep[..., None], r, passes=2)[ok] / den[ok][:, None])
            done |= ok
        out[~done] = self.bg[~done]
        return out

    def logo(self, scale, dark=False, supersample=2):
        """The logo alone, as straight RGBA floats, the banner scaled by `scale`."""
        s = scale * supersample
        W, H = max(1, round(self.w * s)), max(1, round(self.h * s))
        alpha = edge(1 - self.cov[0], W, H, s)
        if self.colors is not None:
            pal = self.palette(dark)
            w = np.stack([edge(c, W, H, s) for c in self.cov[1:]])
            w += 1e-3 * np.clip(np.stack([resize(c, W, H, Image.BILINEAR) for c in self.cov[1:]]), 0, 1)
            color = (w[..., None] * pal[:, None, None, :]).sum(0) / np.maximum(w.sum(0), 1e-6)[..., None]
        else:
            color = np.clip(resize(self.field, W, H, Image.BILINEAR), 0, 1)
        out = np.dstack([np.clip(color, 0, 1), alpha])
        if supersample > 1:
            out = downsample(out, supersample)
        return out


def play_button(cov, ss=16):
    """Redraws a play button (a rounded rectangle, layer 1, with a triangle hole, the last layer)
    as exact shapes measured from the banner, so its long straight edges come out perfectly
    straight instead of following the banner's uneven anti-aliasing."""
    lab, n = label(cov[1] + cov[-1] > 0.5)
    biggest = np.bincount(lab.ravel())[1:].argmax() + 1
    region = binary_dilation(lab == biggest, iterations=2)
    button = (cov[1] + cov[-1]) * region
    tri = cov[-1] * region
    ys, xs = np.nonzero(button > 0.5)
    rows = button[ys.min():ys.max() + 1]
    cols = button[:, xs.min():xs.max() + 1]
    w = rows.sum(1).max()
    h = cols.sum(0).max()
    cy, cx = [(button * g).sum() / button.sum() for g in np.indices(button.shape)]
    r = np.sqrt(max(w * h - button.sum(), 0) / (4 - np.pi))
    tw = tri.sum(1).max()
    th = tri.sum(0).max()
    ty, tx = [(tri * g).sum() / tri.sum() for g in np.indices(tri.shape)]
    tx0 = tx - tw / 3  # a right-pointing triangle's centroid is a third of the way from its base

    H, W = cov.shape[1:]
    from PIL import ImageDraw
    big = Image.new('L', (W * ss, H * ss), 0)
    d = ImageDraw.Draw(big)
    # Pixel centres sit at integer coordinates in the coverage arrays.
    box = [((cx - w / 2 + 0.5) * ss), ((cy - h / 2 + 0.5) * ss), ((cx + w / 2 + 0.5) * ss), ((cy + h / 2 + 0.5) * ss)]
    d.rounded_rectangle(box, radius=r * ss, fill=255)
    new_button = np.asarray(big.reduce(ss)).astype(np.float32) / 255
    big = Image.new('L', (W * ss, H * ss), 0)
    ImageDraw.Draw(big).polygon([((tx0 + 0.5) * ss, (ty - th / 2 + 0.5) * ss), ((tx0 + 0.5) * ss, (ty + th / 2 + 0.5) * ss),
                                 ((tx0 + tw + 0.5) * ss, (ty + 0.5) * ss)], fill=255)
    new_tri = np.asarray(big.reduce(ss)).astype(np.float32) / 255
    near = binary_dilation(button > 0.01, iterations=2) | (new_button > 0)
    others = cov[2:-1].sum(0)
    cov[-1] = np.where(near, new_tri, cov[-1])
    cov[1] = np.where(near, np.clip(new_button - new_tri, 0, 1), cov[1])
    cov[0] = np.where(near, np.clip(1 - new_button - others, 0, 1), cov[0])


def downsample(rgba, f):
    """Box-filters straight RGBA by an integer factor (premultiplied, so edges don't darken)."""
    H, W = rgba.shape[0] // f * f, rgba.shape[1] // f * f
    a = rgba[:H, :W, 3:]
    pm = np.concatenate([rgba[:H, :W, :3] * a, a], -1)
    pm = pm.reshape(H // f, f, W // f, f, 4).mean((1, 3))
    a = pm[..., 3:]
    return np.concatenate([pm[..., :3] / np.maximum(a, 1e-6), a], -1)


def over(dst_rgb, src_rgba, x=0, y=0):
    """Composites straight RGBA floats over an RGB float image at (x, y)."""
    h, w = src_rgba.shape[:2]
    region = dst_rgb[y:y + h, x:x + w]
    s = src_rgba[:region.shape[0], :region.shape[1]]
    a = s[..., 3:]
    dst_rgb[y:y + h, x:x + w] = region * (1 - a) + s[..., :3] * a
    return dst_rgb


def to_image(a):
    return Image.fromarray((np.clip(a, 0, 1) * 255 + 0.5).astype(np.uint8))

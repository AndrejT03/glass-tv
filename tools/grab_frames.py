"""Grabs keyframes from Apple aerials over HTTP range requests (no full download) for curation.

Usage: python grab_frames.py <aerials.json> <out dir> <categories,comma,separated> <fractions,e.g. 0.3,0.6>
Needs `pip install av pillow`.
"""
import io, json, sys, os, urllib.request, concurrent.futures as cf
import av

BLOCK = 512 * 1024

class RangeFile(io.RawIOBase):
    def __init__(self, url):
        self.url, self.pos, self.cache = url, 0, {}
        req = urllib.request.Request(url, method='HEAD')
        self.size = int(urllib.request.urlopen(req, timeout=30).headers['Content-Length'])
    def readable(self): return True
    def seekable(self): return True
    def seek(self, off, whence=0):
        self.pos = off if whence == 0 else self.pos + off if whence == 1 else self.size + off
        return self.pos
    def tell(self): return self.pos
    def block(self, i):
        if i not in self.cache:
            start = i * BLOCK; end = min(self.size, start + BLOCK) - 1
            req = urllib.request.Request(self.url, headers={'Range': f'bytes={start}-{end}'})
            self.cache[i] = urllib.request.urlopen(req, timeout=60).read()
        return self.cache[i]
    def readinto(self, b):
        if self.pos >= self.size: return 0
        i = self.pos // BLOCK; data = self.block(i); off = self.pos - i * BLOCK
        n = min(len(b), len(data) - off); b[:n] = data[off:off + n]; self.pos += n
        return n

def grab(item, fracs, out):
    url, name, ident = item['avc'], item['name'], item['id']
    try:
        f = RangeFile(url)
        c = av.open(io.BufferedReader(f, buffer_size=BLOCK))
        s = c.streams.video[0]; dur = float(s.duration * s.time_base)
        res = []
        for fr in fracs:
            c.seek(int(dur * fr / s.time_base), stream=s, any_frame=False, backward=True)
            for frame in c.decode(s):
                img = frame.to_image().resize((480, 270))
                p = os.path.join(out, f"{ident[:8]}_{int(fr*100):02d}.jpg"); img.save(p, quality=85)
                res.append((p, fr)); break
        mb = len(f.cache) * BLOCK / 1e6
        return name, ident, dur, res, mb
    except Exception as e:
        return name, ident, 0, [], str(e)

items = [a for a in json.load(open(sys.argv[1])) if a['category'] in sys.argv[3].split(',')]
fracs = [float(x) for x in sys.argv[4].split(',')]
with cf.ThreadPoolExecutor(8) as ex:
    for name, ident, dur, res, mb in ex.map(lambda a: grab(a, fracs, sys.argv[2]), items):
        print(f"{ident[:8]} {name:32s} {dur:6.0f}s {mb} {[os.path.basename(p) for p, _ in res]}")

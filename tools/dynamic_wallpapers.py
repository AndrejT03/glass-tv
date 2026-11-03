"""Downloads Apple's dynamic desktop pictures that follow the sun (Big Sur, Catalina, The Beach…)
from Apple's own servers, as macOS does when one is chosen in System Settings, and splits each into
the frames Glass's Dynamic Wallpaper screen saver and the iPhone app's widgets show
(tools/dynamic_frames.swift), with dynamic.json listing them and the sun's position for each frame.

The list and the download addresses come from this Mac's catalog of desktop pictures (MobileAsset);
only the "solar" ones (isSolar in /System/Library/Desktop Pictures/*.madesktop) are taken. The
pictures are Apple's: they stay out of the app and this project, in ~/Library/Caches/GlassTV/dynamic,
and tools/push_dynamic.sh copies them onto your own TV, as push_fonts.sh does with Apple's fonts.

Usage: python3 -I tools/dynamic_wallpapers.py   (prints the folder dynamic.json is in)
"""
import hashlib, json, os, plistlib, re, subprocess, sys, urllib.request, zipfile

CATALOG = '/System/Library/AssetsV2/com_apple_MobileAsset_DesktopPicture/com_apple_MobileAsset_DesktopPicture.xml'
PICTURES = '/System/Library/Desktop Pictures'
CACHE = os.path.expanduser('~/Library/Caches/GlassTV/dynamic')
FRAMES = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'dynamic_frames.swift')

# The order the TV and the phone list them in: the macOS ones newest first, then the photographs.
ORDER = ['Big Sur', 'Monterey Graphic', 'Ventura Graphic', 'Catalina', 'The Beach', 'The Cliffs',
         'The Lake', 'The Desert', 'Solar Gradients']


def solar_ids():
    ids = []
    for name in os.listdir(PICTURES):
        if name.endswith('.madesktop'):
            with open(os.path.join(PICTURES, name), 'rb') as f:
                info = plistlib.load(f)
            if info.get('isSolar'):
                ids.append(info['mobileAssetID'])
    return sorted(ids, key=lambda i: (ORDER.index(i) if i in ORDER else len(ORDER), i))


def slug(name):
    return re.sub(r'[^a-z0-9]+', '-', name.lower()).strip('-')


def download(asset, path):
    """The asset's zip, checked against the catalog's SHA-1; downloaded once."""
    want = asset['_Measurement'].hex()
    if os.path.exists(path) and sha1(path) == want:
        return
    url = asset['__BaseURL'] + asset['__RelativePath']
    print(f"  downloading {asset['_DownloadSize'] / 1e6:.0f} MB from {url}", file=sys.stderr)
    with urllib.request.urlopen(url) as response, open(path + '.part', 'wb') as out:
        while chunk := response.read(1 << 20):
            out.write(chunk)
    if sha1(path + '.part') != want:
        os.remove(path + '.part')
        sys.exit(f"{asset['DesktopPictureID']}: the download doesn't match Apple's catalog")
    os.replace(path + '.part', path)


def sha1(path):
    h = hashlib.sha1()
    with open(path, 'rb') as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def main():
    with open(CATALOG, 'rb') as f:
        assets = {a['DesktopPictureID']: a for a in plistlib.load(f)['Assets']}
    out = os.path.join(CACHE, 'out')
    # A compiled helper: the interpreter would compile it again for each picture.
    helper = os.path.join(CACHE, 'dynamic_frames')
    if not os.path.exists(helper) or os.path.getmtime(helper) < os.path.getmtime(FRAMES):
        subprocess.run(['swiftc', '-O', '-o', helper, FRAMES], check=True)
    wallpapers = []
    for name in solar_ids():
        asset = assets.get(name)
        if asset is None:
            print(f'{name}: not in the catalog', file=sys.stderr)
            continue
        print(name, file=sys.stderr)
        key = slug(name)
        os.makedirs(os.path.join(CACHE, 'zips'), exist_ok=True)
        archive = os.path.join(CACHE, 'zips', key + '.zip')
        download(asset, archive)
        frames = os.path.join(out, key)
        done = os.path.join(frames, 'frames.json')
        if not os.path.exists(done) or os.path.getmtime(done) < os.path.getmtime(archive):
            # Each picture is unpacked into a folder of its own.
            unpacked = os.path.join(CACHE, 'heic', key)
            os.makedirs(unpacked, exist_ok=True)
            with zipfile.ZipFile(archive) as z:
                heic = next(n for n in z.namelist() if n.startswith('AssetData/') and n.lower().endswith('.heic'))
                target = os.path.join(unpacked, 'picture.heic')
                with z.open(heic) as src, open(target, 'wb') as dst:
                    while chunk := src.read(1 << 20):
                        dst.write(chunk)
            info = subprocess.run([helper, target, frames], check=True, capture_output=True, text=True).stdout
            with open(done, 'w') as f:
                f.write(info)
            os.remove(target)
        with open(done) as f:
            info = json.load(f)
        wallpapers.append({'id': key, 'name': name, **info})
    with open(os.path.join(out, 'dynamic.json'), 'w') as f:
        json.dump({'version': 1, 'wallpapers': wallpapers}, f, ensure_ascii=False, separators=(',', ':'))
    print(len(wallpapers), 'wallpapers,', sum(w['frames'] for w in wallpapers), 'frames', file=sys.stderr)
    print(out)


main()

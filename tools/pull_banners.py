"""Pulls each TV app's banner (best density) without copying whole APKs: unzip -p on the TV."""
import os, re, subprocess, sys, zipfile, json

ADB = os.path.expanduser('~/Library/Android/sdk/platform-tools/adb')
AAPT = os.path.expanduser('~/Library/Android/sdk/build-tools/36.1.0/aapt2')
S, out_dir, pkgs_file = sys.argv[1], sys.argv[2], sys.argv[3]
work = os.path.join(out_dir, '_work'); os.makedirs(work, exist_ok=True)
RANK = {'xxxhdpi': 6, 'xxhdpi': 5, 'xhdpi': 4, 'tvdpi': 3, 'hdpi': 3, 'mdpi': 2, '': 1, 'nodpi': 1, 'anydpi': 0}

def adb(*a): return subprocess.run([ADB, '-s', S, *a], capture_output=True).stdout
def unzip(apk, entry): return adb('exec-out', 'unzip', '-p', apk, entry)
def mini(manifest, arsc, name):
    p = os.path.join(work, name)
    with zipfile.ZipFile(p, 'w') as z:
        z.writestr('AndroidManifest.xml', manifest); z.writestr('resources.arsc', arsc)
    return p
def aapt(*a): return subprocess.run([AAPT, *a], capture_output=True, text=True, errors='replace').stdout

result = {}
for pkg in [l.strip() for l in open(pkgs_file) if l.strip()]:
    apks = [l.replace('package:', '').strip() for l in adb('shell', 'pm', 'path', pkg).decode().splitlines() if l.strip()]
    base = next((a for a in apks if a.endswith('base.apk')), apks[0] if apks else None)
    if not base: continue
    manifest = unzip(base, 'AndroidManifest.xml')
    mb = mini(manifest, unzip(base, 'resources.arsc'), pkg + '.zip')
    badging = aapt('dump', 'badging', mb)
    label = re.search(r"application-label:'([^']*)'", badging)
    label = label.group(1) if label else pkg
    tree = aapt('dump', 'xmltree', '--file', 'AndroidManifest.xml', mb)
    banners = re.findall(r':banner\(0x010103f2\)=@(0x[0-9a-f]+)', tree)
    icons = re.findall(r':icon\(0x01010002\)=@(0x[0-9a-f]+)', tree)
    best = None
    for kind, refs in (('banner', banners), ('icon', icons)):
      if best: break
      for apk in apks:
        dump = aapt('dump', 'resources', mb if apk == base else mini(manifest, unzip(apk, 'resources.arsc'), pkg + '_s.zip'))
        for ref in refs[:1]:
            m = re.search(r'resource ' + ref + r' \S+\n((?:\s+\(.*\n)+)', dump)
            if not m: continue
            for cfg, path, typ in re.findall(r'\(([^)]*)\) \(file\) (\S+) type=(\w+)', m.group(1)):
                if typ == 'XML': continue
                dens = next((d for d in RANK if d and d in cfg.split('-')), '')
                score = RANK.get(dens, 1)
                if best is None or score > best[0]: best = (score, apk, path, dens, kind)
    if best:
        data = unzip(best[1], best[2])
        ext = os.path.splitext(best[2])[1] or '.png'
        fn = os.path.join(out_dir, pkg + ext)
        open(fn, 'wb').write(data)
        result[pkg] = {'label': label, 'kind': best[4], 'density': best[3], 'file': os.path.basename(fn)}
    else:
        result[pkg] = {'label': label, 'kind': None, 'density': None, 'file': None}
    print(f"{pkg:45s} {label[:28]:28s} {result[pkg]['kind']} {result[pkg]['density']}")
json.dump(result, open(os.path.join(out_dir, 'apps.json'), 'w'), ensure_ascii=False, indent=1)

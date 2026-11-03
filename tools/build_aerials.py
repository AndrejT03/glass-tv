"""Builds app/src/main/assets/aerials.json from Apple's tvOS aerial manifest (resources-16.tar)."""
import json, sys, os

# Hand-picked shots that make calm home-screen wallpapers (id prefix -> position in the video).
# Chosen from keyframes pulled with tools/grab_frames.py.
STILLS = {
    'EE01F02D': 0.30, '2F52E34C': 0.30, 'B8F204CE': 0.30, 'E5D58CC2': 0.60, 'B004358B': 0.60,
    '25A6CFB2': 0.30, '4109D42A': 0.60, 'E5799A24': 0.60, 'E161929C': 0.30, '3954A7C4': 0.30,
    '0C747C29': 0.60, '8590D0C5': 0.30, 'E54D5AFE': 0.30, 'AFA22C08': 0.30, '3D729CFC': 0.30,
    '82BD33C9': 0.30, '12E0343D': 0.60, 'E334A6D2': 0.30, '22162A9B': 0.60, '258A6797': 0.60,
}

src, out = sys.argv[1], sys.argv[2]
entries = json.load(open(os.path.join(src, 'entries.json')))
strings = json.load(open(os.path.join(src, 'en.json')))
cats = {c['id']: strings.get(c['localizedNameKey'], '') for c in entries['categories']}
result = []
for a in entries['assets']:
    pois = sorted(((int(t), strings.get(k, '')) for t, k in a.get('pointsOfInterest', {}).items()), key=lambda p: p[0])
    entry = {
        'id': a['id'],
        'name': a['accessibilityLabel'],
        'category': cats.get((a.get('categories') or [''])[0], ''),
        'poi': [[t, s.replace('\n', ' ').replace(' ', ' ')] for t, s in pois if s],
        'hd': a['url-1080-SDR'],
        'uhd': a['url-4K-SDR'],
        'avc': a['url-1080-H264'],
    }
    still = STILLS.get(a['id'][:8])
    if still is not None:
        entry['still'] = still
    result.append(entry)
json.dump(result, open(out, 'w'), ensure_ascii=False, separators=(',', ':'))
print(len(result), 'aerials,', sum('still' in e for e in result), 'wallpaper stills')

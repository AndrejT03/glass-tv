"""Renders the Top Shelf designs from the Claude Design canvas into the launcher's assets.

Each artboard in design/canvas/project/*.dc.html is a fixed 1920x1080 composition. This lifts its
markup out of the canvas wrapper, points the uploaded logos (/_blob/<id>) at the local copies in
design/logos/, renders it with headless Chromium and writes app/src/main/assets/topshelf/<package>.jpg,
which the launcher prefers over the Top Shelf it generates from a banner.

Usage: python export_topshelf.py <path to chrome-headless-shell> [board ...]
Needs Pillow.
"""
import json
import os
import re
import subprocess
import sys
import tempfile

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BOARDS = os.path.join(ROOT, 'design', 'canvas', 'project')
LOGOS = os.path.join(ROOT, 'design', 'logos')
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'topshelf')
MAP = json.load(open(os.path.join(ROOT, 'design', 'boards.json')))


def standalone(dc: str) -> str:
    body = dc.split('</helmet>', 1)[1].split('</x-dc>', 1)[0]
    for blob, logo in MAP['assets'].items():
        body = body.replace(f'/_blob/{blob}', 'file://' + os.path.join(LOGOS, logo))
    return f'<!doctype html><html><head><meta charset="utf-8"><style>body{{margin:0;background:#000}}</style></head><body>{body}</body></html>'


def render(chrome: str, board: str, package: str):
    html = standalone(open(os.path.join(BOARDS, board)).read())
    with tempfile.TemporaryDirectory() as tmp:
        page = os.path.join(tmp, 'page.html')
        shot = os.path.join(tmp, 'shot.png')
        open(page, 'w').write(html)
        subprocess.run([
            chrome, '--headless', '--hide-scrollbars', '--force-device-scale-factor=1',
            '--allow-file-access-from-files', '--window-size=1920,1080',
            f'--screenshot={shot}', 'file://' + page,
        ], check=True, capture_output=True)
        # Full-resolution colour (4:4:4): chroma subsampling would soften red and blue logo edges.
        Image.open(shot).convert('RGB').save(os.path.join(OUT, package + '.jpg'), quality=92, optimize=True, subsampling=0)


def main():
    chrome = sys.argv[1]
    only = set(sys.argv[2:])
    os.makedirs(OUT, exist_ok=True)
    for board, package in MAP['boards'].items():
        if only and board not in only:
            continue
        if not os.path.exists(os.path.join(BOARDS, board)):
            continue
        render(chrome, board, package)
        print('rendered', board, '->', package)


if __name__ == '__main__':
    main()

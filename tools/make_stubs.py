"""Builds code-less preview apps (real label + real banner) so the emulator shows the TV's apps."""
import json, os, subprocess, sys, shutil
from xml.sax.saxutils import escape
BT = os.path.expanduser('~/Library/Android/sdk/build-tools/36.1.0')
JAR = os.path.expanduser('~/Library/Android/sdk/platforms/android-36/android.jar')
KS = os.path.expanduser('~/.android/debug.keystore')
src, out = sys.argv[1], sys.argv[2]
apps = json.load(open(os.path.join(src, 'apps.json')))
os.makedirs(out, exist_ok=True)
def run(*a): r = subprocess.run(a, capture_output=True, text=True); assert r.returncode == 0, (a, r.stderr); return r
for pkg, v in apps.items():
    if not v['file'] or pkg.startswith('com.google.android.youtube.tvmusic'): continue
    name = 'preview.' + pkg.replace('-', '_')
    d = os.path.join(out, pkg); shutil.rmtree(d, ignore_errors=True)
    dens = v['density'] if v['density'] not in (None, '', 'nodpi', 'anydpi') else 'nodpi'
    resdir = os.path.join(d, 'res', 'drawable-' + dens); os.makedirs(resdir)
    ext = os.path.splitext(v['file'])[1]
    shutil.copy(os.path.join(src, v['file']), os.path.join(resdir, 'art' + ext))
    attr = 'android:banner="@drawable/art" android:icon="@drawable/art"' if v['kind'] == 'banner' else 'android:icon="@drawable/art"'
    open(os.path.join(d, 'AndroidManifest.xml'), 'w').write(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{name}" android:versionCode="1">
  <uses-feature android:name="android.software.leanback" android:required="false"/>
  <application android:label="{escape(v['label'])}" {attr} android:hasCode="false">
    <activity android:name="{name}.Main" android:exported="true">
      <intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LEANBACK_LAUNCHER"/></intent-filter>
    </activity>
  </application>
</manifest>''')
    run(f'{BT}/aapt2', 'compile', '--dir', os.path.join(d, 'res'), '-o', os.path.join(d, 'res.zip'))
    run(f'{BT}/aapt2', 'link', '-o', os.path.join(d, 'u.apk'), '-I', JAR, '--manifest', os.path.join(d, 'AndroidManifest.xml'), os.path.join(d, 'res.zip'), '--min-sdk-version', '26', '--target-sdk-version', '31')
    run(f'{BT}/zipalign', '-f', '4', os.path.join(d, 'u.apk'), os.path.join(d, 'a.apk'))
    run(f'{BT}/apksigner', 'sign', '--ks', KS, '--ks-pass', 'pass:android', '--out', os.path.join(out, name + '.apk'), os.path.join(d, 'a.apk'))
    print('built', name, v['label'])

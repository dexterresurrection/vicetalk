#!/usr/bin/env python3
"""Changes of Telegram Desktop's Telegram/build/prepare/prepare.py for our CI:
    patch_prepare.py <prepare.py>
* breakpad and crashpad are not built: crash reports are off in our builds, and they need ATL that the 14.44
  toolset of the runner image lacks;
* after every finished library its intermediate files are removed at once (same idea as the cleanup in
  Telegram's own CI), otherwise Qt does not fit on the runner's disk.
"""
import re
import sys

HOOK = '''def tgxFreeUp(stage):
    keep = ('.lib', '.a', '.exe', '.dll', '.h', '.hpp', '.inc', '.cmake', '.pc', '.rs', '.toml', '.json', '.py', '.def',
            '.natvis', '.prl', '.qml')
    # only the big ones: the Rust stages (tlottie, wallet-engine) are sources for a later stage
    heavy = ('ffmpeg', 'openssl3', 'libvpx', 'libjxl', 'openal-soft', 'dav1d', 'mozjpeg', 'libheif', 'libavif', 'openh264', 'tg_owt', 'libwebp', 'opus')
    if not (stage['name'] in heavy or stage['name'].startswith('qt_')):
        return
    root = os.path.join(stage['directory'], stage['name'])
    freed = 0
    for dp, _, files in os.walk(root):
        flat = dp.replace(os.sep, '/') + '/'
        if '/cache_keys/' in flat or '/include/' in flat or '/patches/' in flat or '/nv-codec-headers/' in flat:
            continue
        for f in files:
            if not f.lower().endswith(keep):
                try:
                    freed += os.path.getsize(os.path.join(dp, f))
                    os.remove(os.path.join(dp, f))
                except OSError:
                    pass
    print('  freed %d MB in %s' % (freed // 1048576, stage['name']), flush=True)


def runStages():
'''


def main():
    p = sys.argv[1]
    s = open(p, encoding='utf-8').read()
    for name in ('breakpad', 'crashpad'):
        s, n = re.subn(r"^stage\('%s', \"\"\"" % name, "if False: stage('%s', \"\"\"" % name, s, flags=re.M)
        if n != 1:
            sys.exit(f'prepare.py: stage {name}: {n}')
    if s.count('        writeCacheKey(stage)\n') != 1 or s.count('def runStages():\n') != 1:
        sys.exit('prepare.py: anchors moved')
    s = s.replace('        writeCacheKey(stage)\n', '        writeCacheKey(stage)\n        tgxFreeUp(stage)\n')
    s = s.replace('def runStages():\n', HOOK)
    open(p, 'w', encoding='utf-8').write(s)
    print('prepare.py patched: no breakpad/crashpad, cleanup after each library')


if __name__ == '__main__':
    main()

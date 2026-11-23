#!/usr/bin/env python3
"""Icons from the design kit (/root/cloudvpn-design) into this repository under our resource names.
    python3 tools/import_icons.py [kit dir]
Cloud: appicon/cloudtalk (or the earlier appicon/cloudgram); Vice: appicon/vice/vice-talk. Writes:
  android/overlay/TMessagesProj/src/main/res/{mipmap-*/tgx_ic_{cloud,vice}*.png, mipmap-anydpi-v26/*.xml, drawable/…}
  desktop/overlay/Telegram/Resources/art/icon*.png, icon256.ico, logo_256*.png, icon_round512@2x.png (Cloud look)
  desktop/installer/assets{,-vice}/ (icon.ico, wizard.bmp, header.bmp), brand/*.png|ico (downloads, logos.py)"""
import os
import re
import shutil
import subprocess
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KIT = sys.argv[1] if len(sys.argv) > 1 else '/root/cloudvpn-design'
CLOUD = next(p for p in (f'{KIT}/appicon/cloudtalk', f'{KIT}/appicon/cloudgram') if os.path.isdir(p))
CPFX = os.path.basename(CLOUD)            # cloudtalk | cloudgram (file name prefix in the kit)
VICE = f'{KIT}/appicon/vice/vice-talk'
RES = f'{ROOT}/android/overlay/TMessagesProj/src/main/res'


def cp(src, dst):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copyfile(src, dst)


def android(kitdir, name):
    a = f'{kitdir}/android'
    for d in sorted(os.listdir(a)):
        if not d.startswith('mipmap-') or d == 'mipmap-anydpi-v26':
            continue
        for f in os.listdir(f'{a}/{d}'):
            m = re.fullmatch(r'ic_launcher(_round|_foreground|_background|_monochrome)?\.png', f)
            if m:
                cp(f'{a}/{d}/{f}', f'{RES}/{d}/{name}{m.group(1) or ""}.png')
    bg = f'{a}/drawable/cv_ic_launcher_background.xml'
    bgref = f'@mipmap/{name}_background'
    if os.path.exists(bg):
        cp(bg, f'{RES}/drawable/{name}_background.xml')
        bgref = f'@drawable/{name}_background'
    for variant in ('', '_round'):
        x = ('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
             f'    <background android:drawable="{bgref}" />\n'
             f'    <foreground android:drawable="@mipmap/{name}_foreground" />\n'
             f'    <monochrome android:drawable="@mipmap/{name}_monochrome" />\n</adaptive-icon>\n')
        os.makedirs(f'{RES}/mipmap-anydpi-v26', exist_ok=True)
        open(f'{RES}/mipmap-anydpi-v26/{name}{variant}.xml', 'w').write(x)


def main():
    android(CLOUD, 'tgx_ic_cloud')
    android(VICE, 'tgx_ic_vice')
    # brand files: logos.py source, downloads images, Windows icons
    cp(f'{CLOUD}/png/{CPFX}-1024.png', f'{ROOT}/brand/cloud-1024.png')
    cp(f'{VICE}/png/vice-talk-1024.png', f'{ROOT}/brand/vice-1024.png')
    # the logo of the first screen (Brand.introIcon): 512 px is enough for ICON_HEIGHT_DP = 150 dp at 3.5x
    nod = f'{RES}/drawable-nodpi'
    os.makedirs(nod, exist_ok=True)
    Image.open(f'{CLOUD}/png/{CPFX}-1024.png').convert('RGBA').resize((512, 512), Image.LANCZOS).save(f'{nod}/tgx_logo_cloud.png')
    Image.open(f'{VICE}/png/vice-talk-1024.png').convert('RGBA').resize((512, 512), Image.LANCZOS).save(f'{nod}/tgx_logo_vice.png')
    cp(f'{CLOUD}/{CPFX}.ico', f'{ROOT}/brand/cloud.ico')
    cp(f'{VICE}/vice-talk.ico', f'{ROOT}/brand/vice.ico')
    cp(f'{CLOUD}/png/{CPFX}-256.png', f'{ROOT}/brand/downloads/talk/icon.png')
    for n in (64, 256, 512):
        cp(f'{VICE}/png/vice-talk-{n}.png', f'{ROOT}/brand/downloads/vice/vice-talk-{n}.png')
    # desktop art (Cloud look inside the app for both lines)
    art = f'{ROOT}/desktop/overlay/Telegram/Resources/art'
    for base, px in (('icon16', 16), ('icon32', 32), ('icon48', 48), ('icon64', 64), ('icon128', 128), ('icon256', 256), ('icon512', 512)):
        for suffix, k in (('', 1), ('@2x', 2)):
            p = f'{CLOUD}/png/{CPFX}-{px * k}.png'
            img = Image.open(p) if os.path.exists(p) else Image.open(f'{CLOUD}/png/{CPFX}-1024.png').resize((px * k, px * k), Image.LANCZOS)
            img.save(f'{art}/{base}{suffix}.png')
    big = Image.open(f'{CLOUD}/png/{CPFX}-1024.png').convert('RGBA')
    big.resize((256, 256), Image.LANCZOS).save(f'{art}/logo_256.png')
    big.resize((256, 256), Image.LANCZOS).save(f'{art}/logo_256_no_margin.png')
    m = Image.new('L', big.size, 0)
    ImageDraw.Draw(m).ellipse([0, 0, big.size[0] - 1, big.size[1] - 1], fill=255)
    rnd = Image.new('RGBA', big.size, (0, 0, 0, 0))
    rnd.paste(big, (0, 0), m)
    rnd.save(f'{art}/icon_round512@2x.png')
    shutil.copyfile(f'{CLOUD}/{CPFX}.ico', f'{art}/icon256.ico')
    # installer pictures
    inst = f'{ROOT}/desktop/installer'
    for f in ('wizard.bmp', 'header.bmp', 'icon.ico'):
        cp(f'{VICE}/installer/{f}', f'{inst}/assets-vice/{f}')
    shutil.copyfile(f'{CLOUD}/{CPFX}.ico', f'{inst}/assets/icon.ico')
    for f in ('wizard.bmp', 'header.bmp'):
        if os.path.exists(f'{inst}/assets/{f}'):
            os.remove(f'{inst}/assets/{f}')
    subprocess.run([sys.executable, '/root/cloudvpn-windows/installer/kit/make_assets.py', f'{CLOUD}/png/{CPFX}-256.png',
                    'Cloud Talk', 'неофициальный клиент Telegram', f'{inst}/assets'], check=True)
    rev = subprocess.run(['git', '-C', KIT, 'log', '-1', '--format=%h'], capture_output=True, text=True).stdout.strip()
    open(f'{ROOT}/brand/KIT-COMMIT', 'w').write(rev + '\n')
    print('icons imported from kit', rev)


if __name__ == '__main__':
    main()

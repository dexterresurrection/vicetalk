#!/usr/bin/env python3
"""Telegram .attheme files of the Vice line from the design kit's tokens (Vice Night / Vice Day):
    tools/make_themes.py <Telegram checkout with the base .attheme files or a dir> <out dir>
Every colour of Telegram's own night / day theme is mapped: neutral backgrounds → the kit's blue-violet (night) or
white (day), Telegram's blue accents → the kit's magenta; texts stay readable (WCAG checks at the end)."""
import colorsys
import json
import os
import re
import struct
import sys

KIT = json.load(open(os.environ.get('VICE_TOKENS', '/root/cloudvpn-design/tokens/vice.json')))['themes']


def hx(s):
    return int(s[1:3], 16) / 255, int(s[3:5], 16) / 255, int(s[5:7], 16) / 255


def argb(v):
    v &= 0xFFFFFFFF
    return (v >> 24) & 255, (v >> 16) & 255, (v >> 8) & 255, v & 255


def to_int(a, r, g, b):
    v = (a << 24) | (round(r * 255) << 16) | (round(g * 255) << 8) | round(b * 255)
    return v - (1 << 32) if v & 0x80000000 else v


def lum(c):
    def f(x):
        return x / 12.92 if x <= .03928 else ((x + .055) / 1.055) ** 2.4
    return .2126 * f(c[0]) + .7152 * f(c[1]) + .0722 * f(c[2])


def contrast(a, b):
    la, lb = sorted((lum(a), lum(b)), reverse=True)
    return (la + .05) / (lb + .05)


def lerp(a, b, t):
    return tuple(x + (y - x) * t for x, y in zip(a, b))


def convert(key, v, name):
    c = KIT[name]['color']
    dark = KIT[name]['dark']
    a, r8, g8, b8 = argb(v)
    r, g, b = r8 / 255, g8 / 255, b8 / 255
    h, s, val = colorsys.rgb_to_hsv(r, g, b)
    hd = h * 360
    fg = bool(re.search(r'Text|Icon|Title|Link|Check|Arrow|Placeholder|Hint', key))
    if s > .22 and 175 <= hd <= 265:                      # Telegram's blues (accents, links, chat bubbles) → magenta
        pink = hx(c['accent']['primary'])
        ph, ps, pv = colorsys.rgb_to_hsv(*pink)
        if fg:
            nv = min(1, val * 1.15 + .12) if dark else min(1, val)
            rr = colorsys.hsv_to_rgb(ph, min(1, ps * .95), nv)
        else:
            rr = colorsys.hsv_to_rgb(ph, min(1, ps), min(1, (val if dark else val * .95)) * (.72 if dark else .92) if val > .55 else val)
        return to_int(a, *rr)
    if s < .22:                                            # greys, black, white
        if dark:
            lo, hi = hx(c['bg']['top']), hx(c['text']['primary'])
            base = hx(c['surface']['base'])
            raised = hx(c['surface']['raised'])
            if val < .30:                                  # backgrounds
                t = min(1, val / .30)
                rr = lerp(lo, lerp(base, raised, min(1, max(0, (t - .35) / .65))), 1 if t > .55 else t / .55)
            elif val < .55:
                rr = lerp(raised, hx(c['text']['dim']), (val - .30) / .25)
            else:
                rr = lerp(hx(c['text']['dim']), hi, min(1, (val - .55) / .4))
            return to_int(a, *rr)
        if val > .9:                                       # white / near-white surfaces keep the light look
            return to_int(a, *lerp((r, g, b), hx(c['surface']['base']), .35))
        if val < .45:                                      # dark texts → the kit's ink
            ink = hx(c['text']['primary'])
            return to_int(a, *lerp(ink, (r, g, b), .18))
        return to_int(a, *lerp((r, g, b), hx(c['text']['dim']), .22))
    return to_int(a, r, g, b)                              # greens, reds, yellows stay


def defaults(java):
    """Telegram's default light colours (ThemeColors.java) → {name: int}: keys that day.attheme leaves at the default blue."""
    src = open(java, encoding='utf-8').read()
    consts = {m.group(1): m.group(2) for m in re.finditer(r'public static final int (\w+) = (0x[0-9a-fA-F]+|-?\d+)', src)}
    names = dict(re.findall(r'colorKeysMap\.put\((key_\w+), "([^"]+)"\)', src))
    out = {}
    for m in re.finditer(r'defaultColors\[(key_\w+)\] = ([^;]+);', src):
        k, e = m.group(1), m.group(2).strip()
        e = consts.get(e, e)
        mm = re.fullmatch(r'(0x[0-9a-fA-F]+|-?\d+)', e)
        if not mm or k not in names:
            continue
        v = int(mm.group(1), 0) & 0xFFFFFFFF
        out[names[k]] = v - (1 << 32) if v & 0x80000000 else v
    return out


def main():
    base, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    default_day = defaults(sys.argv[3]) if len(sys.argv) > 3 else {}
    for name, src, dst in (('vice-night', 'night.attheme', 'vice_night.attheme'), ('vice-day', 'day.attheme', 'vice_day.attheme')):
        lines = []
        seen = {}
        for line in open(os.path.join(base, src), encoding='utf-8'):
            m = re.match(r'^([A-Za-z_0-9]+)=(-?\d+)\s*$', line.strip())
            if not m:
                continue   # WLS= wallpaper lines and headers of the original are not taken over
            nv = convert(m.group(1), int(m.group(2)), name)
            seen[m.group(1)] = nv
            lines.append(f'{m.group(1)}={nv}')
        for key, v in default_day.items():   # keys the base theme leaves at Telegram's defaults
            if key in seen or v in (0, -1):
                continue
            a, r, g, b = argb(v)
            hue, sat, _ = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            # day: everything; night: only the blue accents (a light default background must not get into a dark theme)
            if name == 'vice-day' or (sat > .30 and 175 <= hue * 360 <= 265):
                nv = convert(key, v, name)
                seen[key] = nv
                lines.append(f'{key}={nv}')
        open(os.path.join(out, dst), 'w').write('\n'.join(lines) + '\n')
        # readable texts: key text colours against the main backgrounds
        bad = []
        for txt, bg in (('windowBackgroundWhiteBlackText', 'windowBackgroundWhite'), ('chat_messageTextIn', 'chat_inBubble'),
                        ('chat_messageTextOut', 'chat_outBubble'), ('actionBarDefaultTitle', 'actionBarDefault')):
            if txt in seen and bg in seen:
                f = argb(seen[txt])[1:]
                k = argb(seen[bg])[1:]
                cr = contrast([x / 255 for x in f], [x / 255 for x in k])
                (bad if cr < 3 else [None]).append((txt, bg, round(cr, 1)))
                print(f'{name}: {txt} on {bg}: {cr:.1f}' + ('  LOW' if cr < 3 else ''))
        print(name, len(lines), 'colours →', dst)


if __name__ == '__main__':
    main()

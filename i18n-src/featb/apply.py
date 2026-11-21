#!/usr/bin/env python3
"""Write the desktop Mac-parity B strings (privacy, passcode lock, help, group discovery and
moderation, camera, photo markup) into the 34 iOS .lproj catalogs as one delimited block, so
`./gradlew i18nExtract` carries them to the desktop. English: ALL_EN.json; translations:
tr/<lang>.json. Keys the catalogs already define (the three group.*_member menu items, left in
English in 33 locales on 2026-11-06) are translated IN PLACE instead, and only where the
locale still shows the English text."""
import json, glob, os, re, sys
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, '..', '..', '..', 'OSHI')
BEGIN = '/* __DESKTOP_MAC_PARITY_B_2027__ BEGIN — OSHI Desktop: privacy, passcode lock, help, group discovery/moderation, camera, photo markup. Generated from OSHI-Desktop/i18n-src/featb; edit the JSON, not this block. */'
END = '/* __DESKTOP_MAC_PARITY_B_2027__ END */'
en = json.load(open(os.path.join(HERE, 'ALL_EN.json')))
def esc(v): return v.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n')
LINE = r'^"((?:[^"\\]|\\.)*)"\s*=\s*"((?:[^"\\]|\\.)*)"\s*;'
def write(lang, table):
    p = os.path.join(ROOT, f'{lang}.lproj', 'Localizable.strings')
    s = open(p, encoding='utf-8').read()
    s = re.sub(re.escape(BEGIN) + r'.*?' + re.escape(END) + r'\n?', '', s, flags=re.S).rstrip('\n') + '\n'
    present = {m.group(1): m.group(2) for m in re.finditer(LINE, s, flags=re.M)}
    inplace = 0
    for k, v in list(table.items()):
        if k in present:
            if lang != 'en' and present[k] == esc(en[k]):
                s = re.sub(r'^"' + re.escape(k) + r'"\s*=\s*"(?:[^"\\]|\\.)*"\s*;', lambda m: f'"{k}" = "{esc(v)}";', s, count=1, flags=re.M)
                inplace += 1
            del table[k]
    if table:
        s += '\n' + '\n'.join([BEGIN] + [f'"{k}" = "{esc(table[k])}";' for k in sorted(table)] + [END]) + '\n'
    open(p, 'w', encoding='utf-8').write(s)
    return inplace
langs = sorted(os.path.basename(d)[:-6] for d in glob.glob(os.path.join(ROOT, '*.lproj')))
missing = {}
for lang in langs:
    if lang == 'en':
        write('en', dict(en)); continue
    tf = os.path.join(HERE, 'tr', f'{lang}.json')
    tr = json.load(open(tf)) if os.path.exists(tf) else {}
    gap = [k for k in en if k not in tr]
    if gap: missing[lang] = gap
    n = write(lang, {k: tr[k] for k in en if k in tr})
print(f'{len(en)} English keys; {len(langs)} locales; missing: {missing}')

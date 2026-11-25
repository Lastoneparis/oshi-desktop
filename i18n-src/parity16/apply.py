#!/usr/bin/env python3
"""Write the __DESKTOP_1_6_PARITY__ strings (strings.py) into the iOS .lproj tree as one
delimited block per Localizable.strings, so `./gradlew i18nExtract` carries them to the desktop
in all 34 languages. Same method as ../merge/apply.py."""
import json, glob, os, re, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from strings import K, T, ALIVE_KEYS, ANDROID_DIR
import xml.etree.ElementTree as ET
ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, '..', '..', '..', 'OSHI')
BEGIN = '/* __DESKTOP_1_6_PARITY__ BEGIN — OSHI Desktop strings (ping a place, check-in note, not-on-a-computer). Generated; edit OSHI-Desktop/i18n-src/parity16, not this block. */'
END = '/* __DESKTOP_1_6_PARITY__ END */'
ANDROID_RES = sys.argv[2] if len(sys.argv) > 2 else os.path.expanduser('~/oshi-wt/android-i18n/OSHI-Android/app/src/main/res')
def android_alive(lang):
    d = os.path.join(ANDROID_RES, 'values-' + ANDROID_DIR.get(lang, lang), 'strings_alive_checkin.xml')
    root = ET.parse(d).getroot()
    got = {}
    for e in root.findall('string'):
        v = ''.join(e.itertext()).replace("\\'", "'").replace('\\"', '"')
        if v.startswith('"') and v.endswith('"'): v = v[1:-1]
        got[e.get('name')] = v
    out = {}
    for k in ALIVE_KEYS:
        v = got.get(k.replace('.', '_'))
        if not v: sys.exit(f'{lang}: Android has no {k}')
        n = len(re.findall(r'%\d\$[sd]', v))
        # Android %1$s → iOS %@ (one argument) or %1$@ (several); %1$d stays.
        v = re.sub(r'%(\d)\$s', (lambda m: '%@') if n == 1 else (lambda m: '%' + m.group(1) + '$@'), v)
        if n == 1: v = re.sub(r'%1\$d', '%d', v)
        out[k] = v
    return out
def esc(v): return v.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n')
langs = sorted(os.path.basename(d)[:-6] for d in glob.glob(os.path.join(ROOT, '*.lproj')))
missing = [l for l in langs if l not in T]
if missing: sys.exit(f'no translation for {missing}')
for lang in langs:
    vals = T[lang]
    assert len(vals) == len(K), lang
    table = dict(zip(K, vals))
    alive = android_alive(lang) if lang != 'en' else {}
    if lang != 'en':
        same = [k for k in K if table[k] == T['en'][K.index(k)]]
        if same: sys.exit(f'{lang}: identical to English: {same}')
    p = os.path.join(ROOT, f'{lang}.lproj', 'Localizable.strings')
    s = open(p, encoding='utf-8').read()
    s = re.sub(re.escape(BEGIN) + r'.*?' + re.escape(END) + r'\n?', '', s, flags=re.S).rstrip('\n') + '\n'
    present = set(re.findall(r'^"((?:[^"\\]|\\.)*)"\s*=', s, flags=re.M))
    # An alive.* sentence the iOS catalog already has in this language wins; only the gaps
    # are filled from Android.
    table.update({k: v for k, v in alive.items() if k not in present})
    clash = sorted(k for k in table if k in present)
    if clash: sys.exit(f'{lang}: keys already defined outside the block: {clash}')
    s += '\n' + '\n'.join([BEGIN] + [f'"{k}" = "{esc(table[k])}";' for k in sorted(table)] + [END]) + '\n'
    open(p, 'w', encoding='utf-8').write(s)
json.dump(dict(zip(K, T['en'])), open(os.path.join(HERE, 'en.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print(f'{len(K)} keys × {len(langs)} locales written')

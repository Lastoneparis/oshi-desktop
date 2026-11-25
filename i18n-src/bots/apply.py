#!/usr/bin/env python3
"""Write the desktop bot-commands strings (__DESKTOP_BOTS_2026_11_24__) into the iOS .lproj tree,
the media viewer, scheduled-message notices, bubble labels) into the iOS .lproj tree as one
delimited block per Localizable.strings, so `./gradlew i18nExtract` carries them to the
desktop. English from en.json; translations from tr/<lang>.json."""
import json, glob, os, re, sys
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, '..', '..', '..', 'OSHI')
BEGIN = '/* __DESKTOP_BOTS_2026_11_24__ BEGIN — OSHI Desktop bot commands editor. Generated; edit OSHI-Desktop/i18n-src/bots, not this block. */'
END = '/* __DESKTOP_BOTS_2026_11_24__ END */'
en = json.load(open(os.path.join(HERE, 'en.json'), encoding='utf-8'))
def esc(v): return v.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n')
def write(lang, table):
    p = os.path.join(ROOT, f'{lang}.lproj', 'Localizable.strings')
    s = open(p, encoding='utf-8').read()
    s = re.sub(re.escape(BEGIN) + r'.*?' + re.escape(END) + r'\n?', '', s, flags=re.S).rstrip('\n') + '\n'
    present = set(re.findall(r'^"((?:[^"\\]|\\.)*)"\s*=', s, flags=re.M))
    clash = sorted(k for k in table if k in present)
    if clash: sys.exit(f'{lang}: keys already defined outside the block: {clash}')
    if table:
        lines = [BEGIN] + [f'"{k}" = "{esc(table[k])}";' for k in sorted(table)] + [END]
        s += '\n' + '\n'.join(lines) + '\n'
    open(p, 'w', encoding='utf-8').write(s)
langs = sorted(os.path.basename(d)[:-6] for d in glob.glob(os.path.join(ROOT, '*.lproj')))
missing = {}
for lang in langs:
    if lang == 'en': write('en', en); continue
    tf = os.path.join(HERE, 'tr', f'{lang}.json')
    tr = json.load(open(tf, encoding='utf-8')) if os.path.exists(tf) else {}
    gap = [k for k in en if k not in tr]
    if gap: missing[lang] = len(gap)
    write(lang, {k: tr[k] for k in en if k in tr})
print(f'{len(en)} English keys; {len(langs)} locales; locales missing keys: {missing}')

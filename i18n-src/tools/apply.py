#!/usr/bin/env python3
"""Write the desktop tool-screen strings (pc.*) into the iOS .lproj tree as one delimited
block per Localizable.strings, so `./gradlew i18nExtract` carries them to the desktop.
English comes from en_*.json; translations from tr/<lang>.json. A locale without a
translation file gets no block (the audit test then names it)."""
import json, glob, os, re, sys
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, '..', '..', '..', 'OSHI')
BEGIN = '/* __DESKTOP_TOOLS_2027__ BEGIN — OSHI Desktop tool screens (mail, map, AI, radio, calls, scheduled, peers, devices, covert, limits). Generated; edit the source JSON, not this block. */'
END = '/* __DESKTOP_TOOLS_2027__ END */'
en = {}
for f in sorted(glob.glob(os.path.join(HERE, 'en_*.json'))):
    en.update(json.load(open(f)))
def esc(v): return v.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n')
def write(lang, table):
    p = os.path.join(ROOT, f'{lang}.lproj', 'Localizable.strings')
    s = open(p, encoding='utf-8').read()
    s = re.sub(re.escape(BEGIN) + r'.*?' + re.escape(END) + r'\n?', '', s, flags=re.S).rstrip('\n') + '\n'
    # Never duplicate a key the locale already defines outside this block.
    present = set(re.findall(r'^"((?:[^"\\]|\\.)*)"\s*=', s, flags=re.M))
    table = {k: v for k, v in table.items() if k not in present}
    if table:
        lines = [BEGIN] + [f'"{k}" = "{esc(table[k])}";' for k in sorted(table)] + [END]
        s += '\n' + '\n'.join(lines) + '\n'
    open(p, 'w', encoding='utf-8').write(s)
langs = [os.path.basename(d)[:-6] for d in glob.glob(os.path.join(ROOT, '*.lproj'))]
missing = {}
for lang in sorted(langs):
    if lang == 'en': write('en', en); continue
    tf = os.path.join(HERE, 'tr', f'{lang}.json')
    tr = json.load(open(tf)) if os.path.exists(tf) else {}
    table = {k: tr[k] for k in en if k in tr}
    # Phone keys the desktop reuses that this locale's catalog never translated.
    ff = os.path.join(HERE, 'tr_fill', f'{lang}.json')
    if os.path.exists(ff): table.update(json.load(open(ff)))
    gap = [k for k in en if k not in tr]
    if gap: missing[lang] = len(gap)
    write(lang, table)
print(f'{len(en)} English keys; locales missing keys: {missing}')

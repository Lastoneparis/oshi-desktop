#!/usr/bin/env python3
"""Write the __DESKTOP_MAC_PARITY_A__ strings (forward to many, reply privately, profile photo,
gallery by type) into every OSHI/<lang>.lproj/Localizable.strings as one delimited block, so
`./gradlew i18nExtract` carries them to the desktop. Source of truth: strings.py here."""
import os, re, sys, glob
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from strings import KEYS, T
ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, '..', '..', '..', 'OSHI')
BEGIN = '/* __DESKTOP_MAC_PARITY_A__ BEGIN — OSHI Desktop: forward to many, reply privately, profile photo, gallery by type. Generated; edit OSHI-Desktop/i18n-src/feat-a, not this block. */'
END = '/* __DESKTOP_MAC_PARITY_A__ END */'
def esc(v): return v.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n')
def ph(v): return sorted(re.findall(r'%(?:\d\$)?[@ds]', v))
langs = sorted(os.path.basename(d)[:-6] for d in glob.glob(os.path.join(ROOT, '*.lproj')))
assert set(langs) == set(T), (set(langs) ^ set(T))
for lang in langs:
    vals = T[lang]; assert len(vals) == len(KEYS), lang
    for k, v, e in zip(KEYS, vals, T['en']):
        # positional and plain forms are equivalent for one argument
        norm = lambda xs: sorted(x.replace('1$', '') if len(xs) == 1 else x for x in xs)
        assert norm(ph(v)) == norm(ph(e)), (lang, k, v)
    p = os.path.join(ROOT, f'{lang}.lproj', 'Localizable.strings')
    s = open(p, encoding='utf-8').read()
    s = re.sub(re.escape(BEGIN) + r'.*?' + re.escape(END) + r'\n?', '', s, flags=re.S).rstrip('\n') + '\n'
    present = set(re.findall(r'^"((?:[^"\\]|\\.)*)"\s*=', s, flags=re.M))
    clash = [k for k in KEYS if k in present]
    if clash: sys.exit(f'{lang}: already defined outside the block: {clash}')
    s += '\n' + '\n'.join([BEGIN] + [f'"{k}" = "{esc(v)}";' for k, v in zip(KEYS, vals)] + [END]) + '\n'
    open(p, 'w', encoding='utf-8').write(s)
print(f'{len(KEYS)} keys x {len(langs)} locales')

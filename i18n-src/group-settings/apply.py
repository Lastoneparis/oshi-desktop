#!/usr/bin/env python3
"""Write the __GROUP_INFO_SETTINGS_2026_11_20__ strings (GROUP_INFO_SETTINGS_SPEC.md §5.1, keys
`group.settings.<gs_ suffix>`, plus two desktop-only `desktop.group.settings.*`) into every
OSHI/<lang>.lproj/Localizable.strings as one delimited block, so `./gradlew i18nExtract` carries
them to the desktop. Source of truth: strings.py here. Same method as ../feat-a/apply.py."""
import os, re, sys, glob
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from strings import K, T
ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, '..', '..', '..', 'OSHI')
BEGIN = '/* __GROUP_INFO_SETTINGS_2026_11_20__ BEGIN — group settings (GROUP_INFO_SETTINGS_SPEC §5.1). Generated; edit OSHI-Desktop/i18n-src/group-settings, not this block. */'
END = '/* __GROUP_INFO_SETTINGS_2026_11_20__ END */'
def esc(v): return v.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n')
def ph(v): return sorted(re.findall(r'%(?:\d\$)?[@ds]', v))
langs = sorted(os.path.basename(d)[:-6] for d in glob.glob(os.path.join(ROOT, '*.lproj')))
assert set(langs) == set(T), (set(langs) ^ set(T))
assert len(set(K)) == len(K)
for lang in langs:
    vals = T[lang]; assert len(vals) == len(K), (lang, len(vals), len(K))
    for k, v, e in zip(K, vals, T['en']):
        assert ph(v) == ph(e), (lang, k, v)
        assert v.strip(), (lang, k)
    p = os.path.join(ROOT, f'{lang}.lproj', 'Localizable.strings')
    s = open(p, encoding='utf-8').read()
    s = re.sub(re.escape(BEGIN) + r'.*?' + re.escape(END) + r'\n?', '', s, flags=re.S).rstrip('\n') + '\n'
    present = set(re.findall(r'^"((?:[^"\\]|\\.)*)"\s*=', s, flags=re.M))
    clash = [k for k in K if k in present]
    if clash: sys.exit(f'{lang}: already defined outside the block: {clash}')
    s += '\n' + '\n'.join([BEGIN] + [f'"{k}" = "{esc(v)}";' for k, v in zip(K, vals)] + [END]) + '\n'
    open(p, 'w', encoding='utf-8').write(s)
print(f'{len(K)} keys x {len(langs)} locales')

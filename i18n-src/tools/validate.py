#!/usr/bin/env python3
"""validate.py <lang> [<lang>...] — checks tr/<lang>.json against ALL_EN.json:
same key set, same format placeholders (%@ %d %1$@ %2$d %% ...), protected tokens kept."""
import json, re, sys, os
HERE = os.path.dirname(os.path.abspath(__file__))
en = json.load(open(os.path.join(HERE, 'ALL_EN.json')))
PH = re.compile(r'%(?:\d+\$)?(?:@|d|ld|lld|s|f|\.\d+f)|%%')
PROTECT = ['--mesh', 'mDNS', '.gguf', 'llama.cpp', 'GGUF', 'LoRa', 'Meshtastic', 'OSHI']
ok = True
for lang in sys.argv[1:]:
    p = os.path.join(HERE, 'tr', f'{lang}.json')
    try:
        tr = json.load(open(p, encoding='utf-8'))
    except Exception as e:
        print(f'{lang}: INVALID JSON {e}'); ok = False; continue
    missing = sorted(set(en) - set(tr)); extra = sorted(set(tr) - set(en))
    if missing: print(f'{lang}: missing {missing}'); ok = False
    if extra: print(f'{lang}: extra {extra}'); ok = False
    for k, v in en.items():
        t = tr.get(k)
        if t is None: continue
        if not isinstance(t, str) or not t.strip(): print(f'{lang}: {k} empty'); ok = False; continue
        if sorted(PH.findall(v)) != sorted(PH.findall(t)):
            print(f'{lang}: {k} placeholders {PH.findall(v)} != {PH.findall(t)}'); ok = False
        for w in PROTECT:
            if w in v and w not in t and w not in ('OSHI',):
                print(f'{lang}: {k} lost "{w}"'); ok = False
        if lang != 'en' and t == v and len(v) > 25:
            print(f'{lang}: {k} identical to English (untranslated?)')
    print(f'{lang}: {len(tr)} keys checked')
sys.exit(0 if ok else 1)

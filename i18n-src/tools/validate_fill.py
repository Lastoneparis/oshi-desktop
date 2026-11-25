#!/usr/bin/env python3
import json, re, sys, os
HERE = os.path.dirname(os.path.abspath(__file__))
en = json.load(open(os.path.join(HERE, 'fill_shared_en.json')))
PH = re.compile(r'%(?:\d+\$)?(?:@|d|ld|lld|s|f|\.\d+f)|%%')
ok = True
for lang in sys.argv[1:]:
    try: tr = json.load(open(os.path.join(HERE, 'tr_fill', f'{lang}.json'), encoding='utf-8'))
    except Exception as e: print(lang, 'INVALID', e); ok = False; continue
    if set(tr) != set(en): print(lang, 'key mismatch', set(en) ^ set(tr)); ok = False
    for k, v in en.items():
        t = tr.get(k, '')
        if sorted(PH.findall(v)) != sorted(PH.findall(t)): print(lang, k, 'placeholders'); ok = False
        if not t.strip(): print(lang, k, 'empty'); ok = False
    print(lang, len(tr), 'checked')
sys.exit(0 if ok else 1)

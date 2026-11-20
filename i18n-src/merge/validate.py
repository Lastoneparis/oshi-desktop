#!/usr/bin/env python3
"""validate.py <lang> [...] — tr/<lang>.json against en.json: same keys, same placeholders,
protected tokens kept, no sentence left in English."""
import json, re, sys, os
HERE = os.path.dirname(os.path.abspath(__file__))
en = json.load(open(os.path.join(HERE, 'en.json'), encoding='utf-8'))
PH = re.compile(r'%(?:\d+\$)?(?:@|d|ld|lld|s|f|\.\d+f)|%%')
PROTECT = ['OSHI', 'PARITY.md', 'VIEWS.md', 'REPL', './oshi.sh run --args="--client"', 'MP', '16 MiB', 'skia', 'PNG', 'JPEG', 'GIF', 'WebP', 'BMP', 'HEIF/AVIF', 'MP4/QuickTime', 'Matroska/WebM', 'AVI', 'Ogg', 'MPEG', 'FLAC', 'WAV', 'PDF', 'ZIP', 'V2', 'AI']
WORD = re.compile(r'[^\W\d_]{2,}')
ok = True
for lang in sys.argv[1:]:
    p = os.path.join(HERE, 'tr', f'{lang}.json')
    try: tr = json.load(open(p, encoding='utf-8'))
    except Exception as e: print(f'{lang}: INVALID JSON {e}'); ok = False; continue
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
            if k == 'desktop.destination.ai': continue  # a tab name: the local abbreviation is right (IA, KI, ИИ…)
            if re.search(r'(?<![A-Za-z])' + re.escape(w) + r'(?![A-Za-z])', v) and w not in t:
                print(f'{lang}: {k} lost "{w}"'); ok = False
        if t == v and len(WORD.findall(PH.sub(' ', v))) >= 2:
            print(f'{lang}: {k} identical to English'); ok = False
    print(f'{lang}: {len(tr)} keys checked')
sys.exit(0 if ok else 1)

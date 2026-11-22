#!/usr/bin/env python3
import re, glob, json, os
HERE=os.path.dirname(os.path.abspath(__file__))
src=os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main', 'kotlin')
used=set()
for f in glob.glob(src+'/**/*.kt', recursive=True):
    used |= set(re.findall(r'\bt\("(pc\.[A-Za-z0-9_.]+)"', open(f).read()))
en={}
for f in glob.glob(HERE+'/en_*.json'): en.update(json.load(open(f)))
print('used',len(used),'defined',len(en))
print('MISSING:', sorted(used-set(en)))
print('UNUSED:', sorted(set(en)-used))
